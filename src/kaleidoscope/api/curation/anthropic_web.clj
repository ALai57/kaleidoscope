(ns kaleidoscope.api.curation.anthropic-web
  "Fetches a candidate URL via Anthropic's server-side web_fetch tool, so the
  model (not our infra) handles rendering/redirects for pages that resist a
  plain HTTP GET. The response :content is a mixed block list — never assume
  the first block is the answer; walk it for the web_fetch_tool_result block."
  (:require [kaleidoscope.workflows.llm-executor :as llm-executor]))

(def ^:private web-fetch-tool
  {:type "web_fetch_20260209" :name "web_fetch"})

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private max-resends
  3)

(defn- find-web-fetch-result
  "Walk a mixed :content block list for the web_fetch_tool_result block and
  pull out its document text + final url. Returns nil if not present."
  [content]
  (some (fn [block]
          (when (= "web_fetch_tool_result" (:type block))
            (let [result (:content block)
                  doc    (:content result)
                  text   (get-in doc [:source :data])]
              (when text
                {:final-url (:url result) :raw text}))))
        content))

(defn- build-body [ref]
  {:model      default-model
   :max_tokens 4096
   :tools      [web-fetch-tool]
   :messages   [{:role "user"
                 :content (str "Fetch " (:url ref))}]})

(defn- resend-body [body response]
  (update body :messages conj {:role "assistant" :content (:content response)}))

(defn fetch
  "Fetch `ref`'s :url via Anthropic's web_fetch tool. Returns {:status :ok
  :final-url :raw :content-type :fetcher} on success, {:status :error}
  on any exception, missing document, or exhausted pause_turn re-sends."
  [ref config]
  (try
    (let [api-key (:api-key config)]
      (loop [body (build-body ref) attempt 0]
        (let [response (llm-executor/post-anthropic-sync api-key body)
              found    (find-web-fetch-result (:content response))]
          (cond
            found
            (merge {:status :ok :content-type "text/html" :fetcher :anthropic} found)

            (and (= "pause_turn" (:stop_reason response)) (< attempt max-resends))
            (recur (resend-body body response) (inc attempt))

            :else
            {:status :error}))))
    (catch Exception _
      {:status :error})))
