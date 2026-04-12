(ns kaleidoscope.scoring.llm-scorer
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.scoring.protocol :as protocol]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

(def ^:private anthropic-messages-url
  "https://api.anthropic.com/v1/messages")

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private anthropic-version
  "2023-06-01")

(defn- make-http-client
  []
  (HttpClient/newHttpClient))

(defn- post-anthropic
  "Make a synchronous POST request to the Anthropic messages API.
   Returns the parsed JSON response body."
  [api-key body-map]
  (let [body-str (json/encode body-map)
        request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create anthropic-messages-url))
                     (.header "Content-Type" "application/json")
                     (.header "x-api-key" api-key)
                     (.header "anthropic-version" anthropic-version)
                     (.POST (HttpRequest$BodyPublishers/ofString body-str))
                     (.build))
        client   (make-http-client)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        parsed   (json/decode (.body response) true)]
    (when-not (= 200 status)
      (log/errorf "Anthropic API error %d: %s" status (.body response))
      (throw (ex-info "Anthropic API error"
                      {:status status :body parsed})))
    parsed))

(defn- extract-text
  "Extract text content from an Anthropic messages response."
  [response]
  (-> response :content first :text))

(defn- parse-score-response
  "Parse Claude's JSON score response into the expected shape.
   Dimensions from the API use :name/:value/:rationale keys."
  [text dimensions]
  (try
    (let [parsed     (json/decode text true)
          dim-names  (set (map :name dimensions))
          dim-results (mapv (fn [d]
                              {:dimension-name (or (:name d) (:dimension-name d))
                               :value          (double (or (:value d) 5.0))
                               :rationale      (or (:rationale d) "")})
                            (:dimensions parsed))
          overall    (if (and (:overall parsed) (number? (:overall parsed)))
                       (double (:overall parsed))
                       ;; Compute mean if overall not provided or invalid
                       (let [vals (keep :value (:dimensions parsed))]
                         (if (seq vals)
                           (double (/ (reduce + vals) (count vals)))
                           5.0)))]
      {:overall    overall
       :dimensions dim-results})
    (catch Exception e
      (log/errorf "Failed to parse score response: %s\nText was: %s" e text)
      ;; Fall back to default scores on parse failure
      {:overall    5.0
       :dimensions (mapv (fn [{:keys [name]}]
                           {:dimension-name name
                            :value          5.0
                            :rationale      "Score unavailable (parse error)"})
                         dimensions)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Streaming support for conversation endpoints
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn stream-conversation!
  "Call the Anthropic API in streaming mode and write SSE events to output-stream.

   messages format: [{:role \"user\" :content \"...\"}
                     {:role \"assistant\" :content \"...\"}]

   Each token is written as:  data: {\"token\":\"...\"}\\n\\n
   On completion:             data: [DONE]\\n\\n

   Returns the full assistant response text."
  [api-key system-prompt messages output-stream]
  (let [body-map {:model      default-model
                  :max_tokens 4096
                  :system     system-prompt
                  :stream     true
                  :messages   messages}
        body-str (json/encode body-map)
        request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create anthropic-messages-url))
                     (.header "Content-Type" "application/json")
                     (.header "x-api-key" api-key)
                     (.header "anthropic-version" anthropic-version)
                     (.POST (HttpRequest$BodyPublishers/ofString body-str))
                     (.build))
        client   (make-http-client)
        response (.send client request (HttpResponse$BodyHandlers/ofLines))
        writer   (java.io.OutputStreamWriter. output-stream "UTF-8")
        sb       (StringBuilder.)]
    (try
      (-> (.body response)
          (.forEach
           (reify java.util.function.Consumer
             (accept [_ line]
               (when (str/starts-with? line "data: ")
                 (let [data (subs line 6)]
                   (when (not= data "[DONE]")
                     (try
                       (let [parsed (json/decode data true)
                             delta  (get-in parsed [:delta :text])]
                         (when delta
                           (.append sb delta)
                           (.write writer (str "data: " (json/encode {:token delta}) "\n\n"))
                           (.flush writer)))
                       (catch Exception _)))))))))
      (.write writer "data: [DONE]\n\n")
      (.flush writer)
      (str sb)
      (finally
        (.close writer)))))

(defn call-conversation
  "Non-streaming conversation call. Returns {:role \"assistant\" :content \"...\"}."
  [api-key system-prompt messages]
  (let [body-map {:model      default-model
                  :max_tokens 4096
                  :system     system-prompt
                  :messages   messages}
        response (post-anthropic api-key body-map)]
    {:role    "assistant"
     :content (extract-text response)}))

(defn generate-skills
  "Call the Eng Lead agent to generate a skill tree for a project.
   Returns a vector of skill maps."
  [api-key project]
  (let [user-prompt (agents/skill-generation-prompt project)
        response    (post-anthropic api-key
                                    {:model      default-model
                                     :max_tokens 2048
                                     :system     agents/engineering-lead-agent-system-prompt
                                     :messages   [{:role    "user"
                                                   :content user-prompt}]})
        text        (extract-text response)]
    (try
      (json/decode text true)
      (catch Exception e
        (log/errorf "Failed to parse skill generation response: %s" e)
        []))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LLM Scorer implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord LLMScorer [api-key]
  protocol/IScorer
  (score [_this project score-definition]
    (let [scorer-type  (:scorer-type score-definition)
          system-prompt (agents/get-system-prompt scorer-type)
          user-prompt   (agents/build-scoring-user-prompt project score-definition)
          response      (post-anthropic api-key
                                        {:model      default-model
                                         :max_tokens 2048
                                         :system     system-prompt
                                         :messages   [{:role    "user"
                                                       :content user-prompt}]})
          text          (extract-text response)]
      (parse-score-response text (:dimensions score-definition)))))

(defn make-llm-scorer
  [{:keys [api-key]}]
  (->LLMScorer api-key))

(comment
  (def scorer (make-llm-scorer {:api-key (System/getenv "ANTHROPIC_API_KEY")}))

  (protocol/score scorer
                  {:title       "Personal Project Manager"
                   :description "A tool to track side projects with AI scoring"}
                  {:name        "Intent Clarity"
                   :description "How clearly is the project's intent articulated?"
                   :scorer-type "pm"
                   :dimensions  [{:name "Problem Clarity" :criteria "Is the problem well defined?"}
                                 {:name "User Behaviors"  :criteria "Are user behaviors described?"}]}))
