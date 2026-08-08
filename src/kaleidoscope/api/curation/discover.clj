(ns kaleidoscope.api.curation.discover
  "Grounded candidate discovery via Anthropic's server-side web_search tool.
  Replaces hallucinated URLs: a candidate is kept ONLY if its :ref/:url
  actually appeared in a web_search result seen during the conversation — an
  invented URL is dropped even if the model returns it. The response :content
  is a mixed block list (text, web_search_tool_result, ...) — never assume
  the first or last block is the answer; walk it for both the grounding
  truth (search-result urls) and the candidate JSON (final text block(s))."
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.workflows.llm-executor :as llm-executor]
            [taoensso.timbre :as log]))

(def ^:private web-search-tool
  {:type "web_search_20260209" :name "web_search"})

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private max-resends
  3)

(def ^:private default-over-discover-factor
  2.5)

(def ^:private default-shelf-size
  6)

(defn- target-count
  "How many candidates to ask discovery for: ceil(shelf-size x over-discover-
  factor), so grounding + vetting losses still typically leave >= shelf-size
  survivors in one pass."
  [{:keys [shelf-size over-discover-factor]}]
  (int (Math/ceil (* (or shelf-size default-shelf-size)
                      (or over-discover-factor default-over-discover-factor)))))

(defn- build-body [taste config]
  {:model      default-model
   :max_tokens 4096
   :tools      [web-search-tool]
   :system     agents/librarian-discover-system-prompt
   :messages   [{:role "user"
                 :content (str (agents/format-taste-profile-context taste)
                                "\n\nPropose at least " (target-count config)
                                " candidates.")}]})

(defn- resend-body [body response]
  (update body :messages conj {:role "assistant" :content (:content response)}))

(defn- search-result-urls
  "Collect every url that appeared in a web_search_tool_result block's
  content — the ground truth set a candidate's url must belong to."
  [content]
  (into #{}
        (mapcat (fn [block]
                  (when (= "web_search_tool_result" (:type block))
                    (keep :url (:content block)))))
        content))

(defn- text-blocks [content]
  (keep (fn [block] (when (= "text" (:type block)) (:text block))) content))

(defn- normalize-candidate [{:keys [url] :as candidate}]
  (-> candidate
      (set/rename-keys {:est_time :est-time})
      (assoc :ref {:url url})
      (dissoc :url)))

(defn- parse-candidates
  "Parse the discover response's text block(s) into candidate maps. Returns
  [] on any parse failure — a malformed discovery discovers nothing rather
  than throwing, mirroring api/curation.clj's parse-candidates."
  [content]
  (try
    (let [text (str/join "\n" (text-blocks content))]
      (->> (:candidates (json/decode (llm-executor/extract-json text) true))
           (mapv normalize-candidate)))
    (catch Exception e
      (log/warnf "Failed to parse discover candidates: %s" (.getMessage e))
      [])))

(defn- ground
  "Drop any candidate whose url never appeared in a search result. Logs how
  many were dropped — this is the enforcement point for grounding."
  [candidates urls]
  (let [kept    (filterv #(contains? urls (get-in % [:ref :url])) candidates)
        dropped (- (count candidates) (count kept))]
    (when (pos? dropped)
      (log/infof "Discover: dropped %d ungrounded candidate(s) (url not seen in search results)"
                 dropped))
    kept))

(defn discover
  "Discover candidate resources for `taste` via Anthropic web_search. Returns
  [Candidate], keeping ONLY candidates whose :ref/:url actually appeared in a
  web_search result across the conversation (including any pause_turn
  resends) — an invented url is dropped even if the model returns it.
  Returns [] on any exception or unparseable output."
  [taste config]
  (try
    (let [api-key (:api-key config)]
      (loop [body (build-body taste config) attempt 0 urls #{}]
        (let [response (llm-executor/post-anthropic-sync api-key body)
              content  (:content response)
              urls     (into urls (search-result-urls content))]
          (if (and (= "pause_turn" (:stop_reason response)) (< attempt max-resends))
            (recur (resend-body body response) (inc attempt) urls)
            (ground (parse-candidates content) urls)))))
    (catch Exception e
      (log/warnf "Discover failed: %s" (.getMessage e))
      [])))
