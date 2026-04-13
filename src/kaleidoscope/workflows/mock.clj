(ns kaleidoscope.workflows.mock
  (:require [cheshire.core :as json]
            [kaleidoscope.persistence.workflows :as persistence]
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

  (execute-step! [_this db _project step-run output-stream]
    (log/infof "MockExecutor: executing step '%s'" (:name step-run))
    (persistence/update-step-run! db (:id step-run)
                                  {:status     "running"
                                   :started-at (utils/now)})
    (let [mock-output (str "Mock output for step: " (:name step-run))]
      ;; Emit a couple of token events
      (doseq [tok ["Mock output for step: " (:name step-run)]]
        (write-event! output-stream {:event "token" :data tok}))
      (let [completed (persistence/update-step-run! db (:id step-run)
                                                    {:status       "completed"
                                                     :output       mock-output
                                                     :completed-at (utils/now)})]
        (write-event! output-stream {:event "step_complete" :data completed})
        mock-output)))

  (recommend-workflows [_this _project live-workflows]
    (log/info "MockExecutor: returning mock workflow recommendation")
    (when (seq live-workflows)
      [{:workflow-id (:id (first live-workflows))
        :name        (:name (first live-workflows))
        :confidence  0.85
        :rationale   "Mock recommendation: best available workflow."}])))

(defn make-mock-executor []
  (->MockExecutor))
