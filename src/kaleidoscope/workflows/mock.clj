(ns kaleidoscope.workflows.mock
  (:require [cheshire.core :as json]
            [kaleidoscope.api.tasks :as tasks-api]
            [kaleidoscope.persistence.workflows :as persistence]
            [kaleidoscope.tasks.planner :as task-planner]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.workflows.protocol :as protocol]
            [taoensso.timbre :as log]))

(defn- write-event!
  [^java.io.OutputStream output-stream data]
  (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
    (.write writer (str "data: " (json/encode data) "\n\n"))
    (.flush writer)))

(defrecord MockExecutor []
  protocol/IWorkflowExecutor

  (execute-step! [_this db project step-run output-stream]
    (log/infof "MockExecutor: executing step '%s' (output-kind=%s)"
               (:name step-run) (:output-kind step-run))
    (persistence/update-step-run! db (:id step-run)
                                  {:status     "running"
                                   :started-at (utils/now)})
    (let [output-kind (keyword (or (:output-kind step-run) "text"))]
      (cond
        (= output-kind :clarify)
        ;; Mock clarify: always ready, no user input needed
        (tasks-api/clarify-description-step!
         db (task-planner/make-mock-planner) project step-run output-stream)

        (= output-kind :tasks)
        ;; Mock tasks: generate stub tasks, mark step complete
        (let [mock-planner (task-planner/make-mock-planner)
              user-id      (:user-id project)
              tasks        (tasks-api/run-task-generation!
                            db mock-planner project user-id output-stream
                            {:step-run-id (:id step-run)})
              completed    (persistence/update-step-run!
                            db (:id step-run)
                            {:status       "completed"
                             :output       (format "Generated %d tasks." (count tasks))
                             :completed-at (utils/now)})]
          (write-event! output-stream {:event "step_complete" :data completed})
          completed)

        :else
        ;; Default text path
        (let [mock-output (str "Mock output for step: " (:name step-run))]
          (doseq [tok ["Mock output for step: " (:name step-run)]]
            (write-event! output-stream {:event "token" :data tok}))
          (let [completed (persistence/update-step-run! db (:id step-run)
                                                        {:status       "completed"
                                                         :output       mock-output
                                                         :completed-at (utils/now)})]
            (write-event! output-stream {:event "step_complete" :data completed})
            mock-output)))))

  (recommend-workflows [_this _project live-workflows]
    (log/info "MockExecutor: returning mock workflow recommendation")
    (when (seq live-workflows)
      [{:workflow-id (:id (first live-workflows))
        :name        (:name (first live-workflows))
        :confidence  0.85
        :rationale   "Mock recommendation: best available workflow."}])))

(defn make-mock-executor []
  (->MockExecutor))
