(ns kaleidoscope.api.recipe-scraper
  "Scrape a recipe from a URL into a clean RecipeContent draft. See
  plans/2026-07-10-recipes-feature/PLAN.md.

  Strategy: fetch the page (behind an SSRF guard) → parse schema.org Recipe
  JSON-LD when present → fall back to LLM extraction via the shared Anthropic
  client. The scrape endpoint does not save; it returns a draft the user
  reviews and edits before POSTing."
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.workflows.llm-executor :as llm]
            [taoensso.timbre :as log])
  (:import (java.net URI InetAddress)
           (java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers)
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

(def ^:private http-client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects HttpClient$Redirect/NEVER)
             (.build))))

(defn- fetch-once
  [^URI uri]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri uri)
                    (.header "User-Agent" "Mozilla/5.0 (compatible; KaleidoscopeRecipeBot/1.0)")
                    (.header "Accept" "text/html,application/xhtml+xml")
                    (.timeout (Duration/ofSeconds 10))
                    (.GET)
                    (.build))]
    (.send @http-client request (HttpResponse$BodyHandlers/ofString))))

(defn fetch-html
  "Fetch the page body, following redirects manually so each hop is
  SSRF-checked. Throws ex-info {:type :scrape :reason ...} on failure."
  [url]
  (loop [uri (URI/create url) hops 0]
    (when (> hops max-redirects)
      (throw (ex-info "Too many redirects" {:type :scrape :reason :fetch-failed})))
    (when-let [reason (safe-url? uri)]
      (throw (ex-info "Blocked URL" {:type :scrape :reason (if (= reason :blocked-address) :blocked-url :fetch-failed)})))
    (let [response (fetch-once uri)
          status   (.statusCode response)]
      (cond
        (and (>= status 300) (< status 400))
        (if-let [loc (-> response .headers (.firstValue "location") (.orElse nil))]
          (recur (.resolve uri (URI/create loc)) (inc hops))
          (throw (ex-info "Redirect without location" {:type :scrape :reason :fetch-failed})))

        (not= status 200)
        (throw (ex-info (format "Fetch failed: %d" status) {:type :scrape :reason :fetch-failed}))

        :else
        (let [body (.body response)]
          (if (> (count body) max-body-bytes)
            (subs body 0 max-body-bytes)
            body))))))

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

(defn- instructions->html
  "schema.org recipeInstructions come as a plain string, an array of HowToStep
  (with :text), or HowToSection (with nested :itemListElement). Render to HTML."
  [instructions]
  (cond
    (string? instructions)
    (str "<p>" instructions "</p>")

    (sequential? instructions)
    (let [steps (mapcat (fn [item]
                          (cond
                            (string? item)                                       [item]
                            (= "HowToSection" (get item (keyword "@type")))      (map :text (:itemListElement item))
                            :else                                                [(:text item)]))
                        instructions)]
      (str "<ol>" (str/join (map #(str "<li>" % "</li>") (remove str/blank? steps))) "</ol>"))

    :else ""))

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
  "Extract a RecipeContent draft from JSON-LD, or nil if no Recipe found."
  [html]
  (when-let [node (find-recipe-node (ld-json-blocks html))]
    {:recipe {:title             (:name node)
              :ingredients       (vec (:recipeIngredient node))
              :instructions-html (instructions->html (:recipeInstructions node))
              :servings          (some-> (first-or-self (:recipeYield node)) str)
              :prep-time-minutes (iso-duration->minutes (:prepTime node))
              :cook-time-minutes (iso-duration->minutes (:cookTime node))}
     :suggested-labels  (->suggested-labels node)
     :extraction-method "json-ld"
     :warnings          []}))

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
  "Fetch and extract a recipe draft from `url`. JSON-LD first, LLM fallback
  when an api-key is available. Throws ex-info {:type :scrape :reason ...} on
  failure (:fetch-failed, :no-recipe-found, :blocked-url)."
  [{:keys [api-key]} url]
  (log/infof "Scraping recipe from %s" url)
  (let [html (fetch-html url)]
    (or (parse-json-ld html)
        (if api-key
          (extract-with-llm api-key html)
          (throw (ex-info "No recipe found and no LLM available"
                          {:type :scrape :reason :no-recipe-found}))))))
