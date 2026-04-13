(ns kaleidoscope.api.workflows
  (:require [clojure.string :as str]
            [kaleidoscope.persistence.projects :as projects-persistence]
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
   :steps       [{:name        "Clarify Description"
                  :description (str "If the project description is too brief to generate useful "
                                    "tasks, ask targeted questions to enrich it. Answers are "
                                    "appended to the project description before any other step "
                                    "runs.")
                  :position    0
                  :agent-type  "task_planner"
                  :output-kind "clarify"}
                 {:name        "Evaluate product idea"
                  :description "Evaluate the product idea for clarity, market fit, and user value."
                  :position    1
                  :agent-type  "pm"
                  :output-kind "text"}
                 {:name        "Evaluate Engineering architecture"
                  :description (str "Evaluate Engineering architecture. If the architecture "
                                    "score is below a 5, ask the Engineering Architect to "
                                    "suggest ways to implement the feature and, once those "
                                    "recommendations are added to the document, re-score.")
                  :position    2
                  :agent-type  "engineering_lead"
                  :output-kind "text"}
                 {:name        "Break Down Into Tasks"
                  :description (str "Break the project into atomic, actionable next steps "
                                    "prioritized by urgency. For any unknowns, create "
                                    "time-boxed investigation tasks.")
                  :position    3
                  :agent-type  "task_planner"
                  :output-kind "tasks"}]})

(defn seed-default-workflows!
  "Seed the default workflow for a user. If the workflow already exists but has
   a different number of steps than the current definition, replace its steps."
  [db user-id]
  (let [existing     (persistence/get-workflows db user-id)
        existing-map (into {} (map (fn [wf] [(:name wf) wf]) existing))
        wf-name      (:name feature-development-workflow)]
    (if-let [existing-wf (get existing-map wf-name)]
      ;; Workflow exists — update steps if they don't match current definition
      (let [current (persistence/get-workflow db (:id existing-wf))
            want-n  (count (:steps feature-development-workflow))]
        (when (not= (count (:steps current)) want-n)
          (log/infof "Updating steps for default workflow '%s' for user %s" wf-name user-id)
          (persistence/update-workflow! db (:id existing-wf)
                                        {:steps (:steps feature-development-workflow)})))
      ;; Workflow doesn't exist — create it
      (do (log/infof "Seeding default workflow '%s' for user %s" wf-name user-id)
          (persistence/create-workflow! db
                                        (assoc feature-development-workflow
                                               :user-id user-id
                                               :status "live"
                                               :is-default true))))))

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
          (let [result
                (try
                  (wf-protocol/execute-step! executor db project step-run output-stream)
                  (let [updated-step (persistence/get-step-run db (:id step-run))]
                    (if (= "awaiting_input" (:status updated-step))
                      (do (persistence/update-workflow-run! db run-id {:status "awaiting_input"})
                          :awaiting-input)
                      (do (persistence/update-workflow-run! db run-id
                                                            {:current-step (inc (:current-step run))})
                          (let [updated-run (persistence/get-workflow-run db run-id)]
                            (if (and (= "autonomous" (:mode updated-run))
                                     (find-current-step-run updated-run))
                              :continue
                              :done)))))
                  (catch Exception e
                    (log/errorf "Step execution error in run %s: %s" run-id e)
                    (persistence/update-workflow-run! db run-id {:status "failed"})
                    (throw e)))]
            (case result
              :continue       (recur)
              :done           (do (when (all-steps-done? (persistence/get-workflow-run db run-id))
                                    (persistence/update-workflow-run! db run-id
                                                                      {:status       "completed"
                                                                       :completed-at (utils/now)}))
                                  (persistence/get-workflow-run db run-id))
              :awaiting-input (persistence/get-workflow-run db run-id))))))))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Respond to awaiting_input step
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn respond-to-step!
  "Handle a user's answers for an awaiting_input step.
   Appends answers verbatim to project.description, marks the step completed,
   resets the run to in_progress, and resumes autonomous execution in the background.
   Returns the updated run immediately."
  [db executor project-id user-id run-id step-run-id answers]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (let [step-run (persistence/get-step-run db step-run-id)]
      (when (and step-run (= "awaiting_input" (:status step-run)))
        ;; Append answers to project description
        (let [current-desc (or (:description project) "")
              answers-text (str "\n\nAdditional context from user:\n"
                                (str/join "\n" answers))
              new-desc     (str current-desc answers-text)]
          (projects-persistence/update-project! db project-id user-id {:description new-desc}))
        ;; Mark step complete and advance run counter
        (let [run (persistence/get-workflow-run db run-id)]
          (persistence/update-step-run! db step-run-id
                                        {:status       "completed"
                                         :completed-at (utils/now)})
          (persistence/update-workflow-run! db run-id
                                           {:status       "in_progress"
                                            :current-step (inc (:current-step run))})
          ;; Resume workflow in background
          (let [null-os (java.io.ByteArrayOutputStream.)]
            (future
              (try
                (advance-step! db executor project-id user-id run-id null-os)
                (catch Exception e
                  (log/errorf "Background workflow continuation failed for run %s: %s"
                              run-id e)))))
          (persistence/get-workflow-run db run-id))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Auto-start default workflow on project creation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-default-workflow!
  "Seed the default workflow for the user, create a run in autonomous mode,
   and begin execution in a background thread.
   Returns the created run, or nil if no default workflow is configured."
  [db executor project-id user-id]
  (seed-default-workflows! db user-id)
  (when-let [default-wf (persistence/get-default-workflow db user-id)]
    (let [run     (persistence/create-workflow-run! db project-id (:id default-wf) "autonomous")
          null-os (java.io.ByteArrayOutputStream.)]
      (future
        (try
          (advance-step! db executor project-id user-id (:id run) null-os)
          (catch Exception e
            (log/errorf "Background default workflow failed for project %s: %s"
                        project-id e))))
      run)))
