(ns kaleidoscope.api.recipe-scraper
  "Scrape a recipe from a URL into a clean RecipeContent draft. See
  plans/2026-07-10-recipes-feature/PLAN.md.

  Strategy: fetch the page (behind an SSRF guard) → parse schema.org Recipe
  JSON-LD when present → fall back to LLM extraction via the shared Anthropic
  client. The scrape endpoint does not save; it returns a draft the user
  reviews and edits before POSTing."
  (:require [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as str]
            [kaleidoscope.api.firecrawl :as firecrawl]
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
  "Directly fetch the page body, following redirects manually so each hop is
  SSRF-checked. Classifies the terminal status by code only (no body sniffing):
  403/429/503 are treated as a bot block. Throws ex-info
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
        (let [body (or body "")]
          (if (> (count body) max-body-bytes)
            (subs body 0 max-body-bytes)
            body))))))

(defn- fetch-html
  "Fetch tiers: direct fetch first (free); on a bot block, retry through the
  rendering `fetcher` when one is configured. Any other failure — and a bot
  block with no fetcher — propagates."
  [fetcher url]
  (try
    (fetch-direct url)
    (catch clojure.lang.ExceptionInfo e
      (if (and (= :bot-blocked (:reason (ex-data e))) fetcher)
        (do (log/infof "Direct fetch bot-blocked; retrying %s via rendering fetcher" url)
            (firecrawl/fetch-rendered fetcher url))
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
  "Extract verbatim recipe facts from JSON-LD, or nil if no Recipe found.
  Facts, not a draft: `scrape` decides how facts become sections."
  [html]
  (when-let [node (find-recipe-node (ld-json-blocks html))]
    (let [{:keys [steps section-names]} (parse-instructions (:recipeInstructions node))]
      {:title             (:name node)
       :ingredients       (vec (:recipeIngredient node))
       :steps             steps
       :section-names     section-names
       :servings          (some-> (first-or-self (:recipeYield node)) str)
       :prep-time-minutes (iso-duration->minutes (:prepTime node))
       :cook-time-minutes (iso-duration->minutes (:cookTime node))
       :suggested-labels  (->suggested-labels node)})))

(defn- single-section
  [{:keys [ingredients steps]}]
  [{:name nil :ingredients ingredients :steps steps}])

(defn- facts->result
  [{:keys [title servings prep-time-minutes cook-time-minutes suggested-labels]}
   sections extraction-method warnings]
  {:recipe            {:title             title
                       :sections          sections
                       :servings          servings
                       :prep-time-minutes prep-time-minutes
                       :cook-time-minutes cook-time-minutes}
   :suggested-labels  suggested-labels
   :extraction-method extraction-method
   :warnings          warnings})

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
  [{:keys [section-names ingredients]}]
  (boolean (or (seq section-names)
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
  "Ask for a grouping and merge it deterministically. Returns a sections
  vector, or nil when the response is unusable — the caller falls back."
  [api-key {:keys [ingredients steps section-names] :as facts}]
  (try
    (let [user     (str "INGREDIENTS:\n" (numbered ingredients)
                        "\n\nSTEPS:\n" (numbered steps)
                        (when (seq section-names)
                          (str "\n\nCANDIDATE SECTION NAMES:\n"
                               (str/join "\n" section-names))))
          response (llm/post-anthropic-sync
                    api-key
                    {:model      fallback-model
                     :max_tokens 1024
                     :system     grouping-prompt
                     :messages   [{:role "user" :content user}]})
          parsed   (json/decode (llm/extract-json (-> response :content first :text)) true)]
      (when (valid-grouping? facts (:sections parsed))
        (grouping->sections facts (:sections parsed))))
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
  "Extract the recipe from the page text as strict JSON with keys: title (string), ingredients (array of strings, one per ingredient line), instructions_html (string, an <ol> of steps), servings (string or null), prep_time_minutes (integer or null), cook_time_minutes (integer or null), suggested_labels (array of strings). Return ONLY the JSON object, no prose. Strip all blog exposition — keep only the recipe.")

(defn extract-with-llm
  "LLM fallback when JSON-LD is absent. Requires an api-key; returns a draft."
  [api-key html]
  (let [text     (subs (html->text html) 0 (min 50000 (count (html->text html))))
        response (llm/post-anthropic-sync
                  api-key
                  {:model      fallback-model
                   :max_tokens 2048
                   :system     extract-prompt
                   :messages   [{:role "user" :content text}]})
        raw      (-> response :content first :text)
        parsed   (json/decode (llm/extract-json raw) true)]
    {:recipe {:title             (:title parsed)
              :ingredients       (vec (:ingredients parsed))
              :instructions-html (:instructions_html parsed)
              :servings          (:servings parsed)
              :prep-time-minutes (:prep_time_minutes parsed)
              :cook-time-minutes (:cook_time_minutes parsed)}
     :suggested-labels  (vec (:suggested_labels parsed))
     :extraction-method "llm"
     :warnings          []}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Entry point
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn scrape
  "Fetch and extract a recipe draft from `url`. Fetch tiers: direct first, then
  the rendering `fetcher` (when supplied) if the site bot-blocks the direct
  fetch. Extraction: JSON-LD first, LLM fallback when an api-key is available.
  Throws ex-info {:type :scrape :reason ...} on failure (:fetch-failed,
  :bot-blocked, :no-recipe-found, :blocked-url, :render-failed)."
  [{:keys [api-key fetcher]} url]
  (log/infof "Scraping recipe from %s" url)
  (let [html (fetch-html fetcher url)]
    (if-let [facts (parse-json-ld html)]
      (if (sectioned? facts)
        (or (when api-key
              (when-let [sections (group-sections-with-llm api-key facts)]
                (facts->result facts sections "json-ld+llm-sections" [])))
            (facts->result facts (single-section facts) "json-ld"
                           [(if api-key
                              "Sectioned recipe but grouping failed; flattened to one section"
                              "Sectioned recipe but no LLM available; flattened to one section")]))
        (facts->result facts (single-section facts) "json-ld" []))
      (if api-key
        (extract-with-llm api-key html)
        (throw (ex-info "No recipe found and no LLM available"
                        {:type :scrape :reason :no-recipe-found}))))))
