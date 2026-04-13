(ns kaleidoscope.api.workflows
  (:require [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.workflows :as persistence]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.workflows.protocol :as wf-protocol]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default workflow seed data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def feature-development-workflow
  {:name        "Feature Development"
   :description "A structured workflow for evaluating and developing new software features."
   :is-default  true
   :steps       [{:name        "Evaluate product idea"
                  :description "Evaluate product idea"
                  :position    0}
                 {:name        "Evaluate Engineering architecture"
                  :description (str "Evaluate Engineering architecture. If the architecture "
                                    "score is below a 5, ask the Engineering Architect to "
                                    "suggest ways to implement the feature and, once those "
                                    "recommendations are added to the document, re-score.")
                  :position    1}]})

(defn seed-default-workflows!
  "Seed the default workflows for a user if they don't already have them."
  [db user-id]
  (let [existing       (persistence/get-workflows db user-id)
        existing-names (set (map :name existing))]
    (when-not (contains? existing-names (:name feature-development-workflow))
      (log/infof "Seeding default workflow '%s' for user %s"
                 (:name feature-development-workflow) user-id)
      (persistence/create-workflow! db
                                    (assoc feature-development-workflow
                                           :user-id user-id
                                           :status "live"
                                           :is-default true)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow CRUD
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-workflows
  [db user-id]
  (do (seed-default-workflows! db user-id)
      (persistence/get-workflows db user-id)))

(defn get-workflow
  [db workflow-id]
  (persistence/get-workflow db workflow-id))

(defn create-workflow!
  [db user-id body]
  (persistence/create-workflow! db (assoc body :user-id user-id)))

(defn update-workflow!
  [db workflow-id updates]
  (persistence/update-workflow! db workflow-id updates))

(defn delete-workflow!
  [db workflow-id]
  (persistence/delete-workflow! db workflow-id))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow recommendation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-workflow-recommendation
  "Return ranked workflow recommendations for a project."
  [db executor project-id user-id]
  (when (projects-persistence/get-project db project-id user-id)
    (let [project        (projects-persistence/get-project db project-id user-id)
          live-workflows (persistence/get-live-workflows db user-id)]
      (wf-protocol/recommend-workflows executor project live-workflows))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow runs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-workflow-runs
  [db project-id user-id]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/get-workflow-runs db project-id)))

(defn get-workflow-run
  [db run-id project-id user-id]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/get-workflow-run db run-id)))

(defn create-run!
  "Create a workflow run for a project. Returns the new run."
  [db project-id user-id {:keys [workflow-id mode]}]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/create-workflow-run! db project-id workflow-id (or mode "manual"))))

(defn update-run-mode!
  "Switch a run between manual and autonomous mode."
  [db run-id project-id user-id mode]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/update-workflow-run! db run-id {:mode mode})
    (persistence/get-workflow-run db run-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Step execution
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- find-current-step-run
  "Return the first pending step_run in the run, or nil if none."
  [run]
  (->> (:steps run)
       (filter #(= "pending" (:status %)))
       first))

(defn- all-steps-done?
  "True if every step_run is in a terminal state (completed/skipped/failed)."
  [run]
  (every? #(#{"completed" "skipped" "failed"} (:status %)) (:steps run)))

(defn advance-step!
  "Execute the current pending step. In autonomous mode, continues until all
   steps are done. Writes SSE events to output-stream throughout.
   Returns the final run state."
  [db executor project-id user-id run-id output-stream]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (loop []
      (let [run      (persistence/get-workflow-run db run-id)
            step-run (find-current-step-run run)]
        (cond
          (nil? step-run)
          (do (when (all-steps-done? run)
                (persistence/update-workflow-run! db run-id
                                                  {:status       "completed"
                                                   :completed-at (utils/now)}))
              (persistence/get-workflow-run db run-id))

          :else
          (do
            (try
              (wf-protocol/execute-step! executor db project step-run output-stream)
              (persistence/update-workflow-run! db run-id
                                               {:current-step (inc (:current-step run))})
              (catch Exception e
                (log/errorf "Step execution error in run %s: %s" run-id e)
                (persistence/update-workflow-run! db run-id {:status "failed"})
                (throw e)))
            ;; In autonomous mode, keep going until no more pending steps
            (let [updated-run (persistence/get-workflow-run db run-id)]
              (if (and (= "autonomous" (:mode updated-run))
                       (find-current-step-run updated-run))
                (recur)
                (do (when (all-steps-done? updated-run)
                      (persistence/update-workflow-run! db run-id
                                                        {:status       "completed"
                                                         :completed-at (utils/now)}))
                    (persistence/get-workflow-run db run-id))))))))))

(defn skip-step!
  "Mark the current pending step as skipped, advance current-step counter.
   Returns the updated run."
  [db project-id user-id run-id step-run-id]
  (when (projects-persistence/get-project db project-id user-id)
    (let [run      (persistence/get-workflow-run db run-id)
          step-run (persistence/get-step-run db step-run-id)]
      (when (and run step-run (= "pending" (:status step-run)))
        (persistence/update-step-run! db step-run-id
                                      {:status       "skipped"
                                       :completed-at (utils/now)})
        (persistence/update-workflow-run! db run-id
                                         {:current-step (inc (:current-step run))})
        (when (all-steps-done? (persistence/get-workflow-run db run-id))
          (persistence/update-workflow-run! db run-id
                                           {:status       "completed"
                                            :completed-at (utils/now)}))
        (persistence/get-workflow-run db run-id)))))

(defn run-custom-step!
  "Insert a custom step at the next available position and execute it immediately.
   Writes SSE token events to output-stream. On completion, re-runs classification
   and returns {:step-run <completed-step-run> :recommendation [...]}.
   The SSE stream is left open so the caller can write [DONE] after."
  [db executor project-id user-id run-id
   {:keys [name description agent-type] :as custom-step}
   output-stream]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (let [position (persistence/next-custom-step-position db run-id)
          step-run (persistence/create-custom-step-run!
                    db run-id
                    {:name        name
                     :description description
                     :agent-type  (or agent-type "coach")
                     :position    position})]
      (wf-protocol/execute-step! executor db project step-run output-stream)
      ;; Re-classify after custom step
      (let [live-workflows  (persistence/get-live-workflows db user-id)
            recommendation  (try
                              (wf-protocol/recommend-workflows executor project live-workflows)
                              (catch Exception e
                                (log/errorf "Re-classification after custom step failed: %s" e)
                                []))
            completed-run   (persistence/get-workflow-run db run-id)]
        {:run            completed-run
         :recommendation recommendation}))))
