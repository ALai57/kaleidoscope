(ns kaleidoscope.api.workflows
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.persistence.briefs :as briefs-persistence]
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
   :is-default  false
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

(def autonomous-team-review-workflow
  {:name        "Autonomous Team Review"
   :description (str "Advisors review the brief in parallel, the team lead synthesizes feedback "
                     "and refines or clarifies autonomously. Task generation begins when the team "
                     "is satisfied.")
   :is-default  true
   :steps       [{:name           "PM Review"
                  :description    "Evaluate the product idea for clarity, market fit, and user value."
                  :position       0
                  :agent-type     "pm"
                  :output-kind    "score"
                  :execution-mode "parallel"}
                 {:name           "Engineering Review"
                  :description    "Evaluate the technical architecture, design decisions, and implementation strategy."
                  :position       1
                  :agent-type     "engineering_lead"
                  :output-kind    "score"
                  :execution-mode "parallel"}
                 {:name           "Team Lead"
                  :description    "Synthesize advisor feedback, compute trajectory and deltas, then decide the next action."
                  :position       2
                  :agent-type     "judge"
                  :output-kind    "decision"
                  :execution-mode "fan_in"
                  :loop-until     "proceed"}
                 {:name           "Generate Tasks"
                  :description    (str "Break the project into atomic, actionable next steps. "
                                       "For any gaps identified by the team, generate investigation tasks.")
                  :position       3
                  :agent-type     "task_planner"
                  :output-kind    "tasks"
                  :execution-mode "sequential"}]})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Scrutiny configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private scrutiny-configs
  {"quick"    {:scrutiny      "quick"
               :max-rounds    1
               :thresholds    {:pm 5.5 :engineering_lead 5.0 :default 5.0}
               :deadband      0.5}
   "standard" {:scrutiny      "standard"
               :max-rounds    2
               :thresholds    {:pm 6.5 :engineering_lead 6.0 :default 6.0}
               :deadband      0.5}
   "rigorous" {:scrutiny      "rigorous"
               :max-rounds    3
               :thresholds    {:pm 7.5 :engineering_lead 7.0 :default 7.0}
               :deadband      0.5}})

(defn- scrutiny-config [level]
  (get scrutiny-configs (or level "standard") (get scrutiny-configs "standard")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Seeding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn seed-default-workflows!
  "Seed the default workflows for a user. Creates or updates steps as needed."
  [db user-id]
  (let [existing     (persistence/get-workflows db user-id)
        existing-map (into {} (map (fn [wf] [(:name wf) wf]) existing))]
    (doseq [wf-def [feature-development-workflow autonomous-team-review-workflow]]
      (let [wf-name (:name wf-def)]
        (if-let [existing-wf (get existing-map wf-name)]
          ;; Workflow exists — update steps and/or is-default if changed
          (let [current   (persistence/get-workflow db (:id existing-wf))
                want-n    (count (:steps wf-def))
                step-diff (not= (count (:steps current)) want-n)
                flag-diff (not= (boolean (:is-default existing-wf))
                                (boolean (:is-default wf-def)))]
            (when (or step-diff flag-diff)
              (log/infof "Updating workflow '%s' for user %s (steps=%s is-default=%s)"
                         wf-name user-id step-diff flag-diff)
              (persistence/update-workflow! db (:id existing-wf)
                                            (cond-> {}
                                              step-diff (assoc :steps (:steps wf-def))
                                              flag-diff (assoc :is-default (:is-default wf-def))))))
          ;; Workflow doesn't exist — create it
          (do (log/infof "Seeding workflow '%s' for user %s" wf-name user-id)
              (persistence/create-workflow! db
                                            (assoc wf-def
                                                   :user-id user-id
                                                   :status "live"))))))))

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
  "Create a workflow run for a project.
   scrutiny controls the review policy (quick | standard | rigorous) for loop workflows."
  [db project-id user-id {:keys [workflow-id mode scrutiny]}]
  (when (projects-persistence/get-project db project-id user-id)
    (let [config (scrutiny-config scrutiny)]
      (persistence/create-workflow-run! db project-id workflow-id (or mode "manual") config))))

(defn update-run-mode!
  "Switch a run between manual and autonomous mode."
  [db run-id project-id user-id mode]
  (when (projects-persistence/get-project db project-id user-id)
    (persistence/update-workflow-run! db run-id {:mode mode})
    (persistence/get-workflow-run db run-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Sequential step execution helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- find-current-step-run
  "Return the first pending sequential step_run in the run, or nil if none."
  [run]
  (->> (:steps run)
       (filter #(and (= "pending" (:status %))
                     (= "sequential" (or (:execution-mode %) "sequential"))))
       first))

(defn- all-steps-done?
  "True if every step_run is in a terminal state (completed/skipped/failed)."
  [run]
  (every? #(#{"completed" "skipped" "failed"} (:status %)) (:steps run)))

(defn- run-sequential-workflow!
  "Execute sequential steps one at a time until done, awaiting_input, or failed.
   This is the original linear execution path."
  [db executor project run-id output-stream]
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
            :awaiting-input (persistence/get-workflow-run db run-id)))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Loop workflow execution helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- write-sse-event!
  [^java.io.OutputStream output-stream data]
  (let [writer (java.io.OutputStreamWriter. output-stream "UTF-8")]
    (.write writer (str "data: " (json/encode data) "\n\n"))
    (.flush writer)))

(defn- run-parallel-steps!
  "Launch all parallel step runs as concurrent futures. Waits for all to complete.
   Failures are caught per-future and logged; the fan_in step will receive failure sentinels."
  [executor db project par-steps output-stream]
  (let [futures (mapv (fn [step-run]
                        (future
                          (try
                            (wf-protocol/execute-step! executor db project step-run output-stream)
                            (catch Exception e
                              (log/errorf "Parallel step %s (%s) failed: %s"
                                          (:id step-run) (:agent-type step-run) e)
                              nil))))
                      par-steps)]
    (doseq [f futures] @f)))

(defn- ensure-initial-brief!
  "Create the initial brief (version 1) from project.description if no brief exists yet."
  [db project]
  (when-not (briefs-persistence/get-latest-brief db (:id project))
    (briefs-persistence/create-brief! db {:project-id (:id project)
                                          :content    (or (:description project) "")
                                          :source     "initial"})))

(defn- loop-steps-complete?
  "Returns true if the most recent judge decision was 'proceed', meaning the loop
   has finished and we should run the sequential tail."
  [db run-id]
  (when-let [record (persistence/get-latest-judge-record db run-id)]
    (= "proceed" (get-in record [:decision :action]))))

(defn- run-sequential-tail!
  "Execute sequential step runs that follow the loop (e.g. task generation).
   Identical logic to run-sequential-workflow! but scoped to sequential-mode step runs."
  [db executor project run-id output-stream]
  (loop []
    (let [run      (persistence/get-workflow-run db run-id)
          step-run (find-current-step-run run)]
      (if (nil? step-run)
        (do (when (every? #(#{"completed" "skipped" "failed"} (:status %))
                           (filter #(= "sequential" (or (:execution-mode %) "sequential"))
                                   (:steps run)))
              (persistence/update-workflow-run! db run-id
                                                {:status       "completed"
                                                 :completed-at (utils/now)}))
            (persistence/get-workflow-run db run-id))
        (do (wf-protocol/execute-step! executor db project step-run output-stream)
            (let [updated (persistence/get-step-run db (:id step-run))]
              (if (= "awaiting_input" (:status updated))
                (do (persistence/update-workflow-run! db run-id {:status "awaiting_input"})
                    (persistence/get-workflow-run db run-id))
                (do (persistence/update-workflow-run! db run-id
                                                      {:current-step (inc (:current-step run))})
                    (recur)))))))))

(defn- run-loop-workflow!
  "Execute the advisor-review loop workflow.

   Flow per iteration:
     1. If no in-progress round: create a new one.
     2. Run parallel (score) steps concurrently.
     3. Run fan_in (judge/decision) step.
     4. Read decision:
        - proceed  → complete round, run sequential tail, done
        - refine   → create refinement step, run it, complete round, loop
        - clarify  → set run to awaiting_input, return

   If loop-steps-complete? is already true (we're resuming after a clarify answer
   that was followed by a proceed), skip straight to the sequential tail."
  [db executor project run-id output-stream]
  (let [run         (persistence/get-workflow-run db run-id)
        workflow-id (:workflow-id run)]

    (ensure-initial-brief! db project)

    ;; If the loop already completed in a previous invocation, run sequential tail
    (if (loop-steps-complete? db run-id)
      (run-sequential-tail! db executor project run-id output-stream)

      ;; Main loop
      (loop []
        (let [current-round (persistence/get-current-round db run-id)]

          ;; If no in-progress round, create the next one
          (let [round (or current-round
                          (let [all-rounds (persistence/get-all-rounds db run-id)
                                next-num   (inc (count all-rounds))
                                r          (persistence/create-round! db run-id next-num)]
                            (persistence/create-round-step-runs! db run-id workflow-id (:id r))
                            r))]

            ;; Run all pending parallel steps concurrently
            (let [par-steps (persistence/get-step-runs-by-round-and-mode
                              db run-id (:id round) "parallel" #{"pending"})]
              (when (seq par-steps)
                (run-parallel-steps! executor db project par-steps output-stream)))

            ;; Run the fan_in (judge) step — find pending or running (re-entry recovery)
            (let [fan-steps (persistence/get-step-runs-by-round-and-mode
                              db run-id (:id round) "fan_in" #{"pending" "running" "awaiting_input"})
                  fan-step  (first fan-steps)]

              (if (nil? fan-step)
                ;; No fan_in step — treat as proceed (shouldn't happen in a well-formed workflow)
                (do (log/warnf "No fan_in step found for round %s — treating as proceed" (:id round))
                    (persistence/complete-round! db (:id round))
                    (run-sequential-tail! db executor project run-id output-stream))

                (do
                  ;; Execute judge — updates step run status and output
                  (try
                    (wf-protocol/execute-step! executor db project fan-step output-stream)
                    (catch Exception e
                      (log/errorf "Fan-in step failed for round %s: %s" (:id round) e)
                      (persistence/update-workflow-run! db run-id {:status "failed"})
                      (throw e)))

                  ;; Read the result
                  (let [updated-fan (persistence/get-step-run db (:id fan-step))]
                    (cond
                      ;; Judge asked for user clarification
                      (= "awaiting_input" (:status updated-fan))
                      (do (persistence/update-workflow-run! db run-id {:status "awaiting_input"})
                          (persistence/get-workflow-run db run-id))

                      ;; Judge decision was written as output
                      :else
                      (let [decision (try
                                       (json/decode (:output updated-fan) true)
                                       (catch Exception _
                                         {:action "proceed" :unresolved []}))]
                        (case (:action decision)

                          "proceed"
                          (do (persistence/complete-round! db (:id round))
                              (run-sequential-tail! db executor project run-id output-stream))

                          "refine"
                          (let [agent-type (:agent_to_refine decision)
                                prompt     (:refinement_prompt decision)
                                ref-step   (persistence/create-refinement-step-run!
                                             db run-id
                                             {:name        (str agent-type " refinement")
                                              :description prompt
                                              :agent-type  agent-type
                                              :round-id    (:id round)})]
                            (try
                              (wf-protocol/execute-step! executor db project ref-step output-stream)
                              (catch Exception e
                                (log/errorf "Refinement step failed: %s" e)))
                            (persistence/complete-round! db (:id round))
                            (recur))

                          ;; Unknown action — proceed defensively
                          (do (log/warnf "Unexpected judge action '%s' — treating as proceed"
                                         (:action decision))
                              (persistence/complete-round! db (:id round))
                              (run-sequential-tail! db executor project run-id output-stream)))))))))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Public: advance step
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn advance-step!
  "Execute the next step(s) in the workflow run. In autonomous mode, continues
   until all steps are done or the run pauses for input.

   For loop workflows (any parallel/fan_in steps): uses the round-based loop executor.
   For sequential workflows: uses the original linear executor."
  [db executor project-id user-id run-id output-stream]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (let [run (persistence/get-workflow-run db run-id)]
      (if (and (:workflow-id run)
               (persistence/workflow-has-loop-steps? db (:workflow-id run)))
        (run-loop-workflow! db executor project run-id output-stream)
        (run-sequential-workflow! db executor project run-id output-stream)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Skip step
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn skip-step!
  "Mark the current pending or awaiting_input step as skipped, advance current-step counter.
   Returns the updated run."
  [db project-id user-id run-id step-run-id]
  (when (projects-persistence/get-project db project-id user-id)
    (let [run      (persistence/get-workflow-run db run-id)
          step-run (persistence/get-step-run db step-run-id)]
      (when (and run step-run (#{"pending" "awaiting_input"} (:status step-run)))
        (persistence/update-step-run! db step-run-id
                                      {:status       "skipped"
                                       :completed-at (utils/now)})
        (persistence/update-workflow-run! db run-id
                                         {:status       "in_progress"
                                          :current-step (inc (:current-step run))})
        (when (all-steps-done? (persistence/get-workflow-run db run-id))
          (persistence/update-workflow-run! db run-id
                                           {:status       "completed"
                                            :completed-at (utils/now)}))
        (persistence/get-workflow-run db run-id)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom steps
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn run-custom-step!
  "Insert a custom step at the next available position and execute it immediately.
   Writes SSE token events to output-stream. On completion, re-runs classification
   and returns {:step-run <completed-step-run> :recommendation [...]}.
   The SSE stream is left open so the caller can write [DONE] after."
  [db executor project-id user-id run-id
   {:keys [name description agent-type] :as _custom-step}
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

   For team-review clarify steps (output_kind = 'decision'):
     - Appends answers to the project brief as a new version.
     - Marks the step completed and completes the current round.
     - Resumes the loop in the background.

   For sequential clarify steps (output_kind = 'clarify'):
     - Appends answers to project.description (original behaviour).
     - Marks the step completed, advances current-step, resumes in background."
  [db executor project-id user-id run-id step-run-id answers]
  (when-let [project (projects-persistence/get-project db project-id user-id)]
    (let [step-run (persistence/get-step-run db step-run-id)]
      (when (and step-run (= "awaiting_input" (:status step-run)))
        (if (= "decision" (:output-kind step-run))
          ;; ── Team-review clarify path ──────────────────────────────────────────
          (let [round-id      (:round-id step-run)
                latest-brief  (briefs-persistence/get-latest-brief db project-id)
                current-text  (or (:content latest-brief) (:description project) "")
                answers-text  (str "\n\n---\nUser clarification:\n" (str/join "\n" answers))]
            (briefs-persistence/create-brief! db
                                              {:project-id        project-id
                                               :content           (str current-text answers-text)
                                               :source            "user_clarification"
                                               :workflow-round-id round-id})
            (persistence/update-step-run! db step-run-id
                                          {:status       "completed"
                                           :completed-at (utils/now)})
            (when round-id
              (persistence/complete-round! db round-id))
            (persistence/update-workflow-run! db run-id {:status "in_progress"})
            (let [null-os (java.io.ByteArrayOutputStream.)]
              (future
                (try
                  (advance-step! db executor project-id user-id run-id null-os)
                  (catch Exception e
                    (log/errorf "Background loop resume failed for run %s: %s" run-id e)))))
            (persistence/get-workflow-run db run-id))

          ;; ── Sequential clarify path (original behaviour) ─────────────────────
          (let [current-desc (or (:description project) "")
                answers-text (str "\n\nAdditional context from user:\n" (str/join "\n" answers))
                new-desc     (str current-desc answers-text)]
            (projects-persistence/update-project! db project-id user-id {:description new-desc})
            (let [run (persistence/get-workflow-run db run-id)]
              (persistence/update-step-run! db step-run-id
                                            {:status       "completed"
                                             :completed-at (utils/now)})
              (persistence/update-workflow-run! db run-id
                                               {:status       "in_progress"
                                                :current-step (inc (:current-step run))})
              (let [null-os (java.io.ByteArrayOutputStream.)]
                (future
                  (try
                    (advance-step! db executor project-id user-id run-id null-os)
                    (catch Exception e
                      (log/errorf "Background workflow continuation failed for run %s: %s"
                                  run-id e)))))
              (persistence/get-workflow-run db run-id))))))))

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
    (let [run     (persistence/create-workflow-run! db project-id (:id default-wf) "autonomous" {})
          null-os (java.io.ByteArrayOutputStream.)]
      (future
        (try
          (advance-step! db executor project-id user-id (:id run) null-os)
          (catch Exception e
            (log/errorf "Background default workflow failed for project %s: %s"
                        project-id e))))
      run)))
