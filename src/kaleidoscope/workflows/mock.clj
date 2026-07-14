(ns kaleidoscope.workflows.mock
  (:require [cheshire.core :as json]
            [kaleidoscope.api.tasks :as tasks-api]
            [kaleidoscope.persistence.interests :as interests-persistence]
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

;; Deterministic discovery pool for tests/dev — the "prototype uses curated
;; mock data" path from the design. Trusted sources get 3 candidates each at
;; relevance 9.0/8.0/7.0; each mock novel source contributes one, including a
;; deliberately irrelevant one (2.0) so threshold-dropping is observable.
(def mock-novel-sources
  [["Quanta Magazine"    8.9]
   ["The Gradient"       8.3]
   ["Works in Progress"  7.7]
   ["Long Now Seminars"  7.1]
   ["Asterisk Magazine"  6.6]
   ["The Browser"        6.2]
   ["Noise Weekly"       2.0]])

(defn mock-candidates
  [{:keys [trusted-sources formats]}]
  (let [kinds   (if (seq formats) (vec formats) ["article" "podcast" "video" "book"])
        kind-at (fn [i] (nth kinds (mod i (count kinds))))]
    (vec
     (concat
      (for [[i source] (map-indexed vector (or trusted-sources []))
            [j rel]    (map-indexed vector [9.0 8.0 7.0])]
        {:kind      (kind-at (+ i j))
         :title     (format "%s pick %d" source (inc j))
         :source    source
         :url       (format "https://example.com/trusted/%d-%d" i j)
         :est_time  "15 min"
         :why       (format "Squarely on your stated intent; %s is on your trusted list." source)
         :relevance rel})
      (for [[i [source rel]] (map-indexed vector mock-novel-sources)]
        {:kind      (kind-at i)
         :title     (format "%s discovery" source)
         :source    source
         :url       (format "https://example.com/novel/%d" i)
         :est_time  "20 min"
         :why       (format "New source worth a look: %s covers this from a fresh angle." source)
         :relevance rel})))))

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

        (and (= output-kind :text)
             (= "librarian" (:agent-type step-run)))
        ;; Mock librarian discovery: deterministic candidates from the
        ;; interest's taste profile (design: mock data in dev/tests).
        (let [interest (interests-persistence/get-interest-by-project-id db (:id project))
              payload  (json/encode {:candidates (mock-candidates
                                                  (or (:taste-profile interest) {}))})]
          (write-event! output-stream {:event "token" :data payload})
          (let [completed (persistence/update-step-run! db (:id step-run)
                                                        {:status       "completed"
                                                         :output       payload
                                                         :completed-at (utils/now)})]
            (write-event! output-stream {:event "step_complete" :data completed})
            payload))

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
