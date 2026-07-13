(ns kaleidoscope.api.recipe-scraper
  "Scrape a recipe from a URL into a clean RecipeContent draft. See
  plans/2026-07-10-recipes-feature/PLAN.md.

  Strategy: fetch the page (behind an SSRF guard) → parse schema.org Recipe
  JSON-LD when present → fall back to LLM extraction via the shared Anthropic
  client. The scrape endpoint does not save; it returns a draft the user
  reviews and edits before POSTing."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.set]
            [clojure.string :as str]
            [kaleidoscope.api.firecrawl :as firecrawl]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
            [kaleidoscope.utils.versioning :as vu]
            [kaleidoscope.workflows.llm-executor :as llm]
            [taoensso.timbre :as log])
  (:import (java.net URI InetAddress)
           (java.time Duration)))

(def ^:private max-body-bytes (* 2 1024 1024)) ;; 2 MB
(def ^:private max-redirects 5)
;; Cheap model for the fallback — a simple JSON-extraction task doesn't need
;; the executor's opus default. See PLAN.md.
(def ^:private fallback-model "claude-haiku-4-5")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; SSRF-guarded fetch
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- blocked-address?
  "True if `addr` is in a range we must never fetch (loopback, private,
  link-local — including the cloud metadata endpoint 169.254.169.254)."
  [^InetAddress addr]
  (or (.isLoopbackAddress addr)
      (.isSiteLocalAddress addr)
      (.isLinkLocalAddress addr)
      (.isAnyLocalAddress addr)
      (.isMulticastAddress addr)))

(defn- safe-url?
  "Validate scheme is http(s) and the host resolves only to public addresses.
  Returns a reason keyword when unsafe, or nil when safe."
  [^URI uri]
  (let [scheme (.getScheme uri)
        host   (.getHost uri)]
    (cond
      (not (#{"http" "https"} scheme)) :bad-scheme
      (str/blank? host)                :bad-host
      :else
      (try
        (if (some blocked-address? (InetAddress/getAllByName host))
          :blocked-address
          nil)
        (catch java.net.UnknownHostException _ :unknown-host)))))

(defn- location-header
  "Case-insensitive lookup of a redirect Location header from a clj-http
  response headers map."
  [headers]
  (some (fn [[k v]] (when (.equalsIgnoreCase (name k) "location") v)) headers))

(defn- fetch-once
  "Single HTTP GET via clj-http. `:redirect-strategy :none` keeps redirects for
  us to follow manually (so each hop is SSRF-checked); `:throw-exceptions false`
  lets us classify the status ourselves rather than clj-http throwing."
  [^String url]
  (http/get url {:headers            {"User-Agent" "Mozilla/5.0 (compatible; KaleidoscopeRecipeBot/1.0)"
                                      "Accept"     "text/html,application/xhtml+xml"}
                 :redirect-strategy  :none
                 :throw-exceptions   false
                 :connection-timeout 10000
                 :socket-timeout     10000
                 :as                 :string}))

(defn fetch-direct
  "Directly fetch the page, following redirects manually so each hop is
  SSRF-checked. Classifies the terminal status by code only (no body sniffing):
  403/429/503 are treated as a bot block. Returns
  {:raw-html :final-url :http-status} on success. Throws ex-info
  {:type :scrape :reason ...} on failure (:blocked-url, :bot-blocked,
  :fetch-failed)."
  [url]
  (loop [uri (URI/create url) hops 0]
    (when (> hops max-redirects)
      (throw (ex-info "Too many redirects" {:type :scrape :reason :fetch-failed})))
    (when-let [reason (safe-url? uri)]
      (throw (ex-info "Blocked URL" {:type :scrape :reason (if (= reason :blocked-address) :blocked-url :fetch-failed)})))
    (let [{:keys [status headers body]} (fetch-once (str uri))]
      (cond
        (and (>= status 300) (< status 400))
        (if-let [loc (location-header headers)]
          (recur (.resolve uri (URI/create loc)) (inc hops))
          (throw (ex-info "Redirect without location" {:type :scrape :reason :fetch-failed})))

        (#{403 429 503} status)
        (do
          (log/warnf "Fetch bot-blocked: status %d" status)
          (throw (ex-info (format "Bot-blocked: %d" status) {:type :scrape :reason :bot-blocked})))

        (not= status 200)
        (do
          (log/errorf "Fetch failed: status %d" status)
          (throw (ex-info (format "Fetch failed: %d" status) {:type :scrape :reason :fetch-failed})))

        :else
        (let [body (or body "")
              html (if (> (count body) max-body-bytes) (subs body 0 max-body-bytes) body)]
          {:raw-html html :final-url (str uri) :http-status status})))))

(defn- fetch-html
  "Fetch tiers: direct fetch first (free); on a bot block, retry through the
  rendering `fetcher` when one is configured. Returns
  {:raw-html :final-url :http-status :fetch-tier}. Any other failure — and a bot
  block with no fetcher — propagates."
  [fetcher url]
  (try
    (assoc (fetch-direct url) :fetch-tier "direct")
    (catch clojure.lang.ExceptionInfo e
      (if (and (= :bot-blocked (:reason (ex-data e))) fetcher)
        (do (log/infof "Direct fetch bot-blocked; retrying %s via rendering fetcher" url)
            {:raw-html    (firecrawl/fetch-rendered fetcher url)
             :final-url   url
             :http-status 200
             :fetch-tier  "firecrawl"})
        (throw e)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; JSON-LD extraction
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ld-json-blocks
  "Return parsed JSON from each <script type=\"application/ld+json\"> block."
  [html]
  (->> (re-seq #"(?is)<script[^>]+type=[\"']application/ld\+json[\"'][^>]*>(.*?)</script>" html)
       (keep (fn [[_ body]]
               (try (json/decode (str/trim body) true)
                    (catch Exception _ nil))))))

(defn- recipe-type?
  [node]
  (let [t (get node (keyword "@type"))]
    (cond
      (string? t)     (= "Recipe" t)
      (sequential? t) (some #{"Recipe"} t)
      :else           false)))

(defn- find-recipe-node
  "Find the Recipe object across the shapes JSON-LD uses: a bare object, a
  top-level array, or an @graph wrapper."
  [blocks]
  (some (fn [block]
          (let [graph (get block (keyword "@graph"))]
            (cond
              (recipe-type? block)   block
              graph                  (first (filter recipe-type? graph))
              (sequential? block)    (first (filter recipe-type? block))
              :else                  nil)))
        blocks))

(defn- iso-duration->minutes
  [s]
  (when (string? s)
    (try (.toMinutes (Duration/parse s))
         (catch Exception _ nil))))

(defn- parse-instructions
  "schema.org recipeInstructions → {:steps [string] :section-names [string]}.
  Steps are verbatim HowToStep text in document order; section-names are
  non-blank HowToSection names in order — the sectioning signal consumed by
  the grouping step. Accepts a plain string, HowToStep[], or HowToSection[]."
  [instructions]
  (let [add-step (fn [acc s]
                   (cond-> acc (not (str/blank? (or s ""))) (update :steps conj s)))]
    (cond
      (string? instructions)
      (add-step {:steps [] :section-names []} instructions)

      (sequential? instructions)
      (reduce (fn [acc item]
                (cond
                  (string? item)
                  (add-step acc item)

                  (= "HowToSection" (get item (keyword "@type")))
                  (reduce add-step
                          (cond-> acc
                            (not (str/blank? (or (:name item) "")))
                            (update :section-names conj (:name item)))
                          (map :text (:itemListElement item)))

                  :else
                  (add-step acc (:text item))))
              {:steps [] :section-names []}
              instructions)

      :else {:steps [] :section-names []})))

(defn- first-or-self [x] (if (sequential? x) (first x) x))

(defn- ->suggested-labels
  [node]
  (->> [(:keywords node) (:recipeCategory node) (:recipeCuisine node)]
       (mapcat (fn [v] (cond (string? v) (str/split v #",\s*")
                             (sequential? v) v
                             :else nil)))
       (map str/trim)
       (remove str/blank?)
       distinct
       vec))

(defn parse-json-ld
  "Extract verbatim ExtractedFacts from JSON-LD, or nil if no Recipe (or none
  with a name). Facts, not a draft: NORMALIZE decides how facts become sections."
  [html]
  (when-let [node (find-recipe-node (ld-json-blocks html))]
    (when-not (str/blank? (:name node))
      (let [{:keys [steps section-names]} (parse-instructions (:recipeInstructions node))]
        {:title             (:name node)
         :ingredients       (vec (:recipeIngredient node))
         :steps             steps
         :section-signals   section-names
         :grouping          nil
         :servings          (some-> (first-or-self (:recipeYield node)) str)
         :prep-time-minutes (iso-duration->minutes (:prepTime node))
         :cook-time-minutes (iso-duration->minutes (:cookTime node))
         :labels            (->suggested-labels node)}))))

(defn- single-section
  [{:keys [ingredients steps]}]
  [{:name nil :ingredients ingredients :steps steps}])

(defn- facts->content
  [{:keys [title servings prep-time-minutes cook-time-minutes]} sections]
  {:title             title
   :sections          sections
   :servings          servings
   :prep-time-minutes prep-time-minutes
   :cook-time-minutes cook-time-minutes})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section signals + LLM grouping
;;
;; The LLM never rewrites content: it returns section names plus indexes into
;; the verbatim ingredient/step lists, the merge is deterministic, and the
;; grouping is mechanically validated. See DESIGN.md §3.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private header-line-re
  ;; "For the frosting:", "Cake:" — short label lines sites embed in
  ;; recipeIngredient because JSON-LD cannot express ingredient sections.
  ;; A false positive only costs one unnecessary grouping call.
  #"(?i)\s*(for\s+the\s+.{1,60}|[^,;]{1,60}:)\s*")

(defn- header-like?
  [line]
  (boolean (re-matches header-line-re (or line ""))))

(defn- sectioned?
  [{:keys [section-signals ingredients]}]
  (boolean (or (seq section-signals)
               (some header-like? ingredients))))

(def ^:private grouping-prompt
  "You are given a recipe's ingredient lines and instruction steps as numbered lists, and possibly candidate section names. Group them into the recipe's components (e.g. cake vs frosting). Return ONLY strict JSON: {\"sections\": [{\"name\": string or null, \"ingredients\": [ingredient indexes], \"steps\": [step indexes]}]}. Rules: use only the given indexes; never rewrite text; every step index appears in exactly one section; every ingredient index appears in exactly one section EXCEPT lines that are section headers (like \"For the frosting:\") — omit header indexes entirely; if the recipe has no real components, return one section with name null containing all indexes.")

(defn- valid-grouping?
  "Steps must be an exact partition of the step indexes; ingredient indexes
  must be in-range and unique, and every non-header ingredient line must be
  assigned (headers may be omitted — they become names, not ingredients)."
  [{:keys [ingredients steps]} sections]
  (let [ing-idxs  (vec (mapcat :ingredients sections))
        step-idxs (vec (mapcat :steps sections))
        required  (set (keep-indexed (fn [i line] (when-not (header-like? line) i))
                                     ingredients))]
    (and (sequential? sections)
         (seq sections)
         (every? #(and (int? %) (<= 0 %) (< % (count ingredients))) ing-idxs)
         (= (count ing-idxs) (count (set ing-idxs)))
         (every? (set ing-idxs) required)
         (every? int? step-idxs)
         (= (sort step-idxs) (vec (range (count steps)))))))

(defn- grouping->sections
  [{:keys [ingredients steps]} sections]
  (mapv (fn [{:keys [name] :as s}]
          {:name        (when-not (str/blank? (or name "")) name)
           :ingredients (mapv ingredients (:ingredients s))
           :steps       (mapv steps (:steps s))})
        sections))

(defn- numbered
  [lines]
  (str/join "\n" (map-indexed (fn [i l] (str i ". " l)) lines)))

(defn- group-sections-with-llm
  "Ask for a grouping and merge it deterministically. Returns
  {:sections [..] :dropped [omitted-line ..] :llm-call {..}} on a valid grouping;
  {:sections nil :llm-call {..}} when the call succeeded but the grouping was
  unusable (caller flattens but the call is still recorded); nil on exception."
  [api-key {:keys [ingredients steps section-signals] :as facts}]
  (try
    (let [user     (str "INGREDIENTS:\n" (numbered ingredients)
                        "\n\nSTEPS:\n" (numbered steps)
                        (when (seq section-signals)
                          (str "\n\nCANDIDATE SECTION NAMES:\n"
                               (str/join "\n" section-signals))))
          request  {:model      fallback-model
                    :max_tokens 1024
                    :system     grouping-prompt
                    :messages   [{:role "user" :content user}]}
          response (llm/post-anthropic-sync api-key request)
          parsed   (json/decode (llm/extract-json (-> response :content first :text)) true)
          llm-call {:purpose :normalize :model fallback-model :request request :response response}]
      (if (valid-grouping? facts (:sections parsed))
        (let [assigned (set (mapcat :ingredients (:sections parsed)))
              dropped  (vec (keep-indexed (fn [i line] (when-not (assigned i) line)) ingredients))]
          {:sections (grouping->sections facts (:sections parsed))
           :dropped  dropped
           :llm-call llm-call})
        {:sections nil :llm-call llm-call}))
    (catch Exception e
      (log/warnf "Section grouping failed: %s" (ex-message e))
      nil)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LLM fallback
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- html->text
  [html]
  (-> html
      (str/replace #"(?is)<script.*?</script>" " ")
      (str/replace #"(?is)<style.*?</style>" " ")
      (str/replace #"(?s)<[^>]+>" " ")
      (str/replace #"\s+" " ")
      str/trim))

(def ^:private extract-prompt
  "Extract the recipe from the page text as strict JSON with keys: title (string), sections (array of {name: string or null, ingredients: array of strings one per ingredient line, steps: array of strings one per instruction step}), servings (string or null), prep_time_minutes (integer or null), cook_time_minutes (integer or null), suggested_labels (array of strings). Use a single section with name null unless the recipe has real components (e.g. cake and frosting). Preserve ingredient lines and step text verbatim. Return ONLY the JSON object, no prose. Strip all blog exposition — keep only the recipe.")

(defn- parse-text
  "Interpret already-plain source text into ExtractedFacts via the LLM. Shared by
  the URL html path (over `html->text`) and the photo transcript path. Asks for
  sections, then flattens to flat ingredient/step lists + a :grouping of index
  ranges NORMALIZE merges deterministically."
  [api-key source-text]
  (let [text     (subs source-text 0 (min 50000 (count source-text)))
        request  {:model      fallback-model
                  :max_tokens 2048
                  :system     extract-prompt
                  :messages   [{:role "user" :content text}]}
        response (llm/post-anthropic-sync api-key request)
        parsed   (json/decode (llm/extract-json (-> response :content first :text)) true)
        raw-secs (:sections parsed)
        sections (if (sequential? raw-secs)
                   (mapv (fn [{:keys [name ingredients steps]}]
                           {:name name :ingredients (vec ingredients) :steps (vec steps)})
                         raw-secs)
                   [])
        flat-ing (vec (mapcat :ingredients sections))
        flat-stp (vec (mapcat :steps sections))
        grouping (loop [secs sections, i 0, j 0, acc []]
                   (if (empty? secs)
                     acc
                     (let [{:keys [name ingredients steps]} (first secs)
                           ni (count ingredients) ns (count steps)]
                       (recur (rest secs) (+ i ni) (+ j ns)
                              (conj acc {:name        name
                                         :ingredients (vec (range i (+ i ni)))
                                         :steps       (vec (range j (+ j ns)))})))))
        no-secs? (empty? sections)]
    {:artifact  {:title             (:title parsed)
                 :ingredients       flat-ing
                 :steps             flat-stp
                 :section-signals   []
                 :grouping          (if no-secs? [{:name nil :ingredients [] :steps []}] grouping)
                 :servings          (:servings parsed)
                 :prep-time-minutes (:prep_time_minutes parsed)
                 :cook-time-minutes (:cook_time_minutes parsed)
                 :labels            (vec (:suggested_labels parsed))}
     :technique :llm
     :llm-calls [{:purpose :parse :model fallback-model :request request :response response}]
     :warnings  (if no-secs? ["LLM returned no sections"] [])}))

(defn- parse-with-llm
  "URL html path: strip to text, then interpret. Thin wrapper over `parse-text`."
  [api-key html]
  (parse-text api-key (html->text html)))

(defn parse
  "PARSE: RawScrape html -> ExtractedFacts. JSON-LD first; LLM fallback when an
  api-key is present; :no-recipe-found when neither yields facts."
  [{:keys [api-key raw-html]}]
  (if-let [facts (parse-json-ld raw-html)]
    {:artifact facts :technique :json-ld}
    (if api-key
      (parse-with-llm api-key raw-html)
      {:outcome      :no-recipe-found
       :error-detail {:message "No recipe found and no LLM available"
                      :reason  :no-recipe-found}})))

(defn normalize
  "NORMALIZE: ExtractedFacts -> RecipeContent. Dispatch: grouping present ->
  :pre-grouped (deterministic index merge); section signals + api-key ->
  :llm-grouping (constrained LLM grouping, flattening on failure); else ->
  :single-section. Always produces content."
  [{:keys [api-key facts]}]
  (let [content (fn [sections] (facts->content facts sections))]
    (cond
      (:grouping facts)
      {:artifact (content (grouping->sections facts (:grouping facts)))
       :technique :pre-grouped}

      (sectioned? facts)
      (if api-key
        (let [{:keys [sections dropped llm-call]} (group-sections-with-llm api-key facts)]
          (if sections
            {:artifact  (content sections)
             :technique :llm-grouping
             :llm-calls (vec (when llm-call [llm-call]))
             :warnings  (when (seq dropped)
                          [(str "Ingredient lines treated as section headers, not ingredients: "
                                (str/join " | " dropped))])}
            {:artifact  (content (single-section facts))
             :technique :single-section
             :llm-calls (vec (when llm-call [llm-call]))
             :warnings  ["Sectioned recipe but grouping failed; flattened to one section"]}))
        {:artifact  (content (single-section facts))
         :technique :single-section
         :warnings  ["Sectioned recipe but no LLM available; flattened to one section"]})

      :else
      {:artifact  (content (single-section facts))
       :technique :single-section})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACQUIRE stage
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn acquire
  "ACQUIRE: fetch the page (SSRF-guarded), direct then rendering fallback.
  StageResult on success: {:artifact RawScrape-data :technique :direct|:firecrawl}.
  On a :type :scrape failure: {:outcome reason :error-detail {..} :raw {:request-url url}}
  so the orchestrator still records an abandoned scrape. Other exceptions propagate."
  [{:keys [fetcher url]}]
  (try
    (let [{:keys [raw-html final-url http-status fetch-tier]} (fetch-html fetcher url)]
      {:artifact  {:request-url url
                   :final-url   final-url
                   :http-status http-status
                   :fetch-tier  fetch-tier
                   :raw-html    raw-html}
       :technique (keyword fetch-tier)})
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type reason]} (ex-data e)]
        (if (= :scrape type)
          {:outcome      reason
           :error-detail {:message (ex-message e) :reason reason}
           :raw          {:request-url url}}
          (throw e))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orchestrator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ledger-entry
  "Fold one StageResult's provenance (technique + calls + warnings) into the
  accumulating ledger under `stage`."
  [ledger stage {:keys [technique llm-calls warnings]}]
  (-> ledger
      (assoc-in [:techniques stage] technique)
      (update :llm-calls into (or llm-calls []))
      (update :warnings  into (or warnings []))))

(defn- process
  "Run ACQUIRE -> PARSE -> NORMALIZE, short-circuiting at the first stage that
  returns an :outcome. Pure: no persistence; a :type :scrape failure is encoded
  as an :outcome (real bugs still throw). Returns {:artifacts :ledger}."
  [{:keys [api-key fetcher url]}]
  (let [empty-ledger {:techniques {} :llm-calls [] :warnings []}
        acq          (acquire {:fetcher fetcher :url url})]
    (if-let [outcome (:outcome acq)]
      {:artifacts {:raw (:raw acq)}
       :ledger    (assoc empty-ledger :outcome outcome :error-detail (:error-detail acq))}
      (let [raw (:artifact acq)
            l1  (ledger-entry empty-ledger :acquire acq)
            prs (parse {:api-key api-key :raw-html (:raw-html raw)})]
        (if-let [outcome (:outcome prs)]
          {:artifacts {:raw raw}
           :ledger    (assoc l1 :outcome outcome :error-detail (:error-detail prs))}
          (let [facts (:artifact prs)
                l2    (ledger-entry l1 :parse prs)
                nrm   (normalize {:api-key api-key :facts facts})]
            {:artifacts {:raw raw :facts facts :content (:artifact nrm)}
             :ledger    (assoc (ledger-entry l2 :normalize nrm) :outcome :success)}))))))

(defn- extraction-method
  "Derive the client-facing extraction-method from the recorded technique kinds."
  [{:keys [parse normalize]}]
  (cond
    (= parse :llm)              "llm"
    (= normalize :llm-grouping) "json-ld+llm-sections"
    :else                       "json-ld"))

(defn- build-scrape-result
  "Reconstruct the ScrapeResult view from the artifacts + ledger."
  [{:keys [content facts]} {:keys [techniques warnings]}]
  {:recipe            content
   :suggested-labels  (vec (:labels facts))
   :extraction-method (extraction-method techniques)
   :warnings          (vec warnings)})

(defn- ->scrape-failure
  [{:keys [outcome error-detail]}]
  (throw (ex-info (or (:message error-detail) "Scrape failed")
                  {:type :scrape :reason outcome})))

(defn extract
  "Run the pipeline WITHOUT persistence; return the ScrapeResult (no run-id) or
  throw {:type :scrape :reason ..}. For extraction where no DB is wired."
  [{:keys [api-key fetcher]} url]
  (let [{:keys [artifacts ledger]} (process {:api-key api-key :fetcher fetcher :url url})]
    (if (= :success (:outcome ledger))
      (build-scrape-result artifacts ledger)
      (->scrape-failure ledger))))

(defn run-pipeline
  "Acquire -> parse -> normalize over `url`; persist one raw_scrapes row and one
  processing_runs row on BOTH the success and short-circuited-failure paths;
  return the ScrapeResult augmented with :scrape-processing-run-id. On a
  :type :scrape failure the failed run is persisted, then the original ex-info is
  re-thrown so the handler's 422 mapping is unchanged."
  [{:keys [database hostname api-key fetcher]} url]
  (log/infof "Running recipe pipeline for %s" url)
  (let [{:keys [artifacts ledger]} (process {:api-key api-key :fetcher fetcher :url url})
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      database (-> (:raw artifacts)
                                   (assoc :hostname hostname :source-kind "url")
                                   (clojure.set/rename-keys {:raw-html :raw-content})))
        {run-id :id} (pipeline-db/create-processing-run!
                      database
                      {:hostname         hostname
                       :raw-scrape-id    raw-id
                       :pipeline-version (:revision (vu/get-version-details))
                       :techniques       (:techniques ledger)
                       :facts            (:facts artifacts)
                       :content          (:content artifacts)
                       :llm-calls        (:llm-calls ledger)
                       :warnings         (:warnings ledger)
                       :outcome          (:outcome ledger)
                       :error-detail     (:error-detail ledger)})]
    (if (= :success (:outcome ledger))
      (assoc (build-scrape-result artifacts ledger) :scrape-processing-run-id run-id)
      (->scrape-failure ledger))))
