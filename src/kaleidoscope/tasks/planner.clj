(ns kaleidoscope.tasks.planner
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.scoring.agents :as agents]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defprotocol ITaskPlanner
  (assess-description [this project conversation-history]
    "Synchronously assess whether the project description is rich enough to generate
     useful tasks. Returns {:ready bool :questions [...] :reply string}.
     Pure domain call — no DB writes, no streaming.")
  (generate-task-list [this project conversation-history]
    "Generate an ordered list of atomic tasks for the project.
     Returns a seq of {:title :description :task_type :estimated_minutes}.
     Pure domain call — no DB writes, no streaming."))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Shared HTTP helpers (Anthropic)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private anthropic-messages-url
  "https://api.anthropic.com/v1/messages")

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private anthropic-version
  "2023-06-01")

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

(defn- build-project-context [project]
  (format "Project Title: %s\nProject Description: %s"
          (:title project)
          (or (:description project) "No description provided.")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; LLM implementation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord LLMTaskPlanner [api-key]
  ITaskPlanner

  (assess-description [_this project _conversation-history]
    (log/infof "LLMTaskPlanner: assessing description for project '%s'" (:title project))
    (try
      (let [user-prompt (str (build-project-context project)
                             "\n\nAssess whether this description is detailed enough to generate "
                             "a useful, actionable task list.")
            response    (post-anthropic-sync
                         api-key
                         {:model      default-model
                          :max_tokens 1024
                          :system     agents/task-planner-clarification-system-prompt
                          :messages   [{:role "user" :content user-prompt}]})
            text        (extract-text response)
            parsed      (json/decode (strip-fences text) true)]
        {:ready     (boolean (:ready parsed))
         :reply     (str (:reply parsed ""))
         :questions (if (:ready parsed) [] [(:reply parsed "")])})
      (catch Exception e
        (log/errorf "assess-description failed: %s" e)
        ;; On error, treat as ready to avoid blocking the workflow
        {:ready true :reply "" :questions []})))

  (generate-task-list [_this project _conversation-history]
    (log/infof "LLMTaskPlanner: generating task list for project '%s'" (:title project))
    (try
      (let [user-prompt (str (build-project-context project)
                             "\n\nGenerate the task list.")
            response    (post-anthropic-sync
                         api-key
                         {:model      default-model
                          :max_tokens 4096
                          :system     agents/task-planner-generation-system-prompt
                          :messages   [{:role "user" :content user-prompt}]})
            text        (extract-text response)
            tasks       (json/decode (strip-fences text) true)]
        (if (sequential? tasks)
          (mapv (fn [{:keys [title description task_type estimated_minutes]}]
                  {:title             title
                   :description       description
                   :task-type         (or task_type "action")
                   :estimated-minutes estimated_minutes})
                tasks)
          (do (log/errorf "generate-task-list: unexpected response shape: %s" text)
              [])))
      (catch Exception e
        (log/errorf "generate-task-list failed: %s" e)
        []))))

(defn make-llm-planner [api-key]
  (->LLMTaskPlanner api-key))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Mock implementation (for testing / mock executor)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord MockTaskPlanner []
  ITaskPlanner

  (assess-description [_this _project _conversation-history]
    {:ready     true
     :reply     "Description looks sufficient."
     :questions []})

  (generate-task-list [_this project _conversation-history]
    [{:title             (str "Research " (:title project))
      :description       "Investigate the problem space and existing solutions."
      :task-type         "research"
      :estimated-minutes 60}
     {:title             (str "Define scope for " (:title project))
      :description       "Write a one-page scope document."
      :task-type         "action"
      :estimated-minutes 30}
     {:title             "Set up project repository"
      :description       "Initialise version control and basic project structure."
      :task-type         "development"
      :estimated-minutes 30}]))

(defn make-mock-planner []
  (->MockTaskPlanner))
