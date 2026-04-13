(ns kaleidoscope.workflows.llm-executor
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.api.agents :as agents-api]
            [kaleidoscope.api.tasks :as tasks-api]
            [kaleidoscope.persistence.workflows :as persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.tasks.planner :as task-planner]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.workflows.protocol :as protocol]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Low-level HTTP helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- post-anthropic-sync
  "Synchronous POST to Anthropic messages API. Returns parsed JSON body."
  [api-key body-map]
  (let [body-str (json/encode body-map)
        request  (-> (HttpRequest/newBuilder)
                     (.uri (URI/create anthropic-messages-url))
                     (.header "Content-Type" "application/json")
                     (.header "x-api-key" api-key)
                     (.header "anthropic-version" anthropic-version)
                     (.POST (HttpRequest$BodyPublishers/ofString body-str))
                     (.build))
        client   (HttpClient/newHttpClient)
        response (.send client request (HttpResponse$BodyHandlers/ofString))
        status   (.statusCode response)
        parsed   (json/decode (.body response) true)]
    (when-not (= 200 status)
      (log/errorf "Anthropic API error %d: %s" status (.body response))
      (throw (ex-info "Anthropic API error" {:status status :body parsed})))
    parsed))

(defn- extract-text [response]
  (-> response :content first :text))

(defn- strip-fences [text]
  (-> text
      str/trim
      (str/replace #"(?s)^```(?:json)?\s*" "")
      (str/replace #"\s*```$" "")
      str/trim))

(defn- write-sse-event!
  "Write a single SSE data event to the output-stream."
  [^java.io.OutputStream output-stream data]
  (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
    (.write writer (str "data: " (json/encode data) "\n\n"))
    (.flush writer)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step execution streaming
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- build-step-system-prompt
  "Return the system prompt for a step's agent-type, augmented with project context.
   Uses custom-prompt if provided, otherwise falls back to the built-in agent prompt."
  [step-run project custom-prompt]
  (let [base-prompt (or custom-prompt
                        (agents/get-system-prompt (:agent-type step-run)))
        project-ctx (format "\n\n---\nProject context:\nTitle: %s\nDescription: %s"
                            (:title project)
                            (or (:description project) "No description provided"))]
    (str base-prompt project-ctx)))

(defn- stream-step-to-output!
  "Stream a Claude response to output-stream using workflow SSE event format.
   Returns the full assistant text."
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
        client   (HttpClient/newHttpClient)
        response (.send client request (HttpResponse$BodyHandlers/ofLines))
        sb       (StringBuilder.)]
    (let [^java.util.stream.Stream lines (.body response)]
      (.forEach lines
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
                                (write-sse-event! output-stream {:event "token" :data delta})))
                            (catch Exception _)))))))))
    (str sb)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow recommendation (classification)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private classifier-system-prompt
  "You are a workflow classifier. Given a project description and a list of
available workflows, rank the workflows by how well they match the project.

Return a JSON array of objects, sorted by confidence (highest first):
[{\"workflow_id\": \"<uuid>\", \"confidence\": <0.0-1.0>, \"rationale\": \"<1-2 sentence explanation>\"}]

Return ONLY the JSON array, no additional text.")

(defn- build-classification-prompt
  [project workflows]
  (let [wf-text (str/join "\n\n"
                  (map-indexed
                    (fn [i {:keys [id name description]}]
                      (format "%d. ID: %s\n   Name: %s\n   Description: %s"
                              (inc i) id name (or description "No description")))
                    workflows))]
    (format
     "Project Title: %s
Project Description: %s

Available workflows:
%s

Rank these workflows by how well they match the project."
     (:title project)
     (or (:description project) "No description provided")
     wf-text)))

(defn- parse-recommendations
  [text workflows]
  (let [wf-by-id (into {} (map (fn [wf] [(str (:id wf)) wf]) workflows))]
    (try
      (->> (json/decode (strip-fences text) true)
           (keep (fn [{:keys [workflow_id confidence rationale]}]
                   (when-let [wf (get wf-by-id workflow_id)]
                     {:workflow-id (parse-uuid workflow_id)
                      :name        (:name wf)
                      :confidence  (double (or confidence 0.0))
                      :rationale   (str rationale)})))
           (sort-by :confidence >)
           vec)
      (catch Exception e
        (log/errorf "Failed to parse workflow classification: %s\nText: %s" e text)
        []))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step execution dispatch (by output-kind)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defmulti execute-step-by-kind!
  "Dispatch step execution based on :output-kind in the step-run.
   Each method receives [executor db project step-run output-stream]."
  (fn [_executor _db _project step-run _output-stream]
    (keyword (or (:output-kind step-run) "text"))))

(defmethod execute-step-by-kind! :text
  [executor db project step-run output-stream]
  (let [user-id       (:user-id project)
        custom-prompt (agents-api/get-custom-system-prompt
                       db user-id (:agent-type step-run))
        system-prompt (build-step-system-prompt step-run project custom-prompt)
        user-message  (:description step-run)
        full-output   (stream-step-to-output! (:api-key executor)
                                              system-prompt
                                              [{:role "user" :content user-message}]
                                              output-stream)
        completed     (persistence/update-step-run! db (:id step-run)
                                                    {:status       "completed"
                                                     :output       full-output
                                                     :completed-at (utils/now)})]
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    full-output))

(defmethod execute-step-by-kind! :clarify
  [executor db project step-run output-stream]
  (let [planner (task-planner/make-llm-planner (:api-key executor))]
    (tasks-api/clarify-description-step! db planner project step-run output-stream)))

(defmethod execute-step-by-kind! :tasks
  [executor db project step-run output-stream]
  (let [planner  (task-planner/make-llm-planner (:api-key executor))
        user-id  (:user-id project)
        tasks    (tasks-api/run-task-generation! db planner project user-id output-stream
                                                 {:step-run-id (:id step-run)})
        summary  (format "Generated %d tasks." (count tasks))
        completed (persistence/update-step-run! db (:id step-run)
                                                {:status       "completed"
                                                 :output       summary
                                                 :completed-at (utils/now)})]
    (write-sse-event! output-stream {:event "step_complete" :data completed})
    summary))

(defmethod execute-step-by-kind! :default
  [executor db project step-run output-stream]
  (log/warnf "Unknown output-kind '%s' for step '%s'; falling back to :text"
             (:output-kind step-run) (:name step-run))
  (execute-step-by-kind! executor db project (assoc step-run :output-kind "text") output-stream))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LLMExecutor record
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord LLMExecutor [api-key]
  protocol/IWorkflowExecutor

  (execute-step! [this db project step-run output-stream]
    (log/infof "LLMExecutor: executing step '%s' (agent=%s output-kind=%s)"
               (:name step-run) (:agent-type step-run) (:output-kind step-run))
    (persistence/update-step-run! db (:id step-run)
                                  {:status     "running"
                                   :started-at (utils/now)})
    (try
      (execute-step-by-kind! this db project step-run output-stream)
      (catch Exception e
        (log/errorf "Step execution failed for step-run %s: %s" (:id step-run) e)
        (persistence/update-step-run! db (:id step-run)
                                      {:status       "failed"
                                       :completed-at (utils/now)})
        (throw e))))

  (recommend-workflows [_this project live-workflows]
    (if (empty? live-workflows)
      []
      (try
        (let [prompt   (build-classification-prompt project live-workflows)
              response (post-anthropic-sync api-key
                                            {:model      default-model
                                             :max_tokens 1024
                                             :system     classifier-system-prompt
                                             :messages   [{:role "user" :content prompt}]})
              text     (extract-text response)]
          (parse-recommendations text live-workflows))
        (catch Exception e
          (log/errorf "Workflow recommendation failed: %s" e)
          [])))))

(defn make-llm-executor [{:keys [api-key]}]
  (->LLMExecutor api-key))
