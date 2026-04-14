(ns kaleidoscope.persistence.workflows
  (:require [honey.sql :as hsql]
            [honey.sql.helpers :as hh]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflows
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-workflows-raw
  (rdbms/make-finder :workflows))

(def ^:private get-workflow-steps-raw
  (rdbms/make-finder :workflow-steps))

(defn get-workflows
  "Return all workflows for a user (including system defaults)."
  [db user-id]
  (get-workflows-raw db {:user-id user-id}))

(defn get-workflow
  "Return a single workflow with its ordered steps."
  [db workflow-id]
  (when-let [wf (first (get-workflows-raw db {:id workflow-id}))]
    (let [steps (next/execute! db
                               (hsql/format {:select   :*
                                             :from     :workflow-steps
                                             :where    [:= :workflow-id workflow-id]
                                             :order-by [[:position :asc]]})
                               {:builder-fn rs/as-unqualified-kebab-maps})]
      (assoc wf :steps (vec steps)))))

(defn get-default-workflow
  "Return the first live default workflow for a user, or nil."
  [db user-id]
  (first (next/execute! db
                        (hsql/format {:select   :*
                                      :from     :workflows
                                      :where    [:and
                                                 [:= :user-id user-id]
                                                 [:= :is-default true]
                                                 [:= :status "live"]]
                                      :limit    1})
                        {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-live-workflows
  "Return all live workflows for a user."
  [db user-id]
  (->> (next/execute! db
                      (hsql/format {:select   :*
                                    :from     :workflows
                                    :where    [:and
                                               [:= :user-id user-id]
                                               [:= :status "live"]]
                                    :order-by [[:created-at :asc]]})
                      {:builder-fn rs/as-unqualified-kebab-maps})
       (mapv (fn [wf]
               (let [steps (next/execute! db
                                          (hsql/format {:select   :*
                                                        :from     :workflow-steps
                                                        :where    [:= :workflow-id (:id wf)]
                                                        :order-by [[:position :asc]]})
                                          {:builder-fn rs/as-unqualified-kebab-maps})]
                 (assoc wf :steps (vec steps)))))))

(defn create-workflow!
  "Create a workflow and its steps in a transaction."
  [db {:keys [user-id name description status is-default steps]}]
  (next/with-transaction [tx db]
    (let [now  (utils/now)
          wf   (first (rdbms/insert! tx
                                     :workflows
                                     {:id          (utils/uuid)
                                      :user-id     user-id
                                      :name        name
                                      :description description
                                      :status      (or status "draft")
                                      :is-default  (boolean is-default)
                                      :created-at  now
                                      :updated-at  now}
                                     :ex-subtype :UnableToCreateWorkflow))]
      (when (seq steps)
        (rdbms/insert! tx
                       :workflow-steps
                       (vec (map-indexed
                              (fn [i {:keys [name description position agent-type output-kind
                                            execution-mode loop-until]}]
                                {:id             (utils/uuid)
                                 :workflow-id    (:id wf)
                                 :position       (or position i)
                                 :name           name
                                 :description    description
                                 :agent-type     (or agent-type "coach")
                                 :output-kind    (or output-kind "text")
                                 :execution-mode (or execution-mode "sequential")
                                 :loop-until     loop-until
                                 :created-at     now
                                 :updated-at     now})
                              steps))
                       :ex-subtype :UnableToCreateWorkflowStep))
      (get-workflow tx (:id wf)))))

(defn update-workflow!
  "Update a workflow's metadata and optionally replace all steps."
  [db workflow-id {:keys [name description status steps]}]
  (next/with-transaction [tx db]
    (let [now (utils/now)]
      (first (rdbms/update! tx
                            :workflows
                            (cond-> {:id         workflow-id
                                     :updated-at now}
                              name        (assoc :name name)
                              description (assoc :description description)
                              status      (assoc :status status)))))
    (when (some? steps)
      ;; Full replace of steps
      (next/execute! tx
                     (hsql/format (-> (hh/delete-from :workflow-steps)
                                      (hh/where [:= :workflow-id workflow-id])))
                     {:builder-fn rs/as-unqualified-kebab-maps})
      (when (seq steps)
        (let [now (utils/now)]
          (rdbms/insert! tx
                         :workflow-steps
                         (vec (map-indexed
                                (fn [i {:keys [name description position agent-type output-kind
                                               execution-mode loop-until]}]
                                  {:id             (utils/uuid)
                                   :workflow-id    workflow-id
                                   :position       (or position i)
                                   :name           name
                                   :description    description
                                   :agent-type     (or agent-type "coach")
                                   :output-kind    (or output-kind "text")
                                   :execution-mode (or execution-mode "sequential")
                                   :loop-until     loop-until
                                   :created-at     now
                                   :updated-at     now})
                                steps))
                         :ex-subtype :UnableToUpdateWorkflowStep))))
    (get-workflow tx workflow-id)))

(defn delete-workflow!
  "Delete a workflow. Returns {:error :cannot-delete-default} if is_default=true."
  [db workflow-id]
  (if-let [wf (first (get-workflows-raw db {:id workflow-id}))]
    (if (:is-default wf)
      (do (log/warnf "Attempted to delete default workflow %s" workflow-id)
          {:error :cannot-delete-default})
      (rdbms/delete! db :workflows workflow-id :ex-subtype :UnableToDeleteWorkflow))
    {:error :not-found}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow runs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-workflow-runs-raw
  (rdbms/make-finder :project-workflow-runs))

(def ^:private get-step-runs-raw
  (rdbms/make-finder :project-workflow-step-runs))

(defn- enrich-run
  "Attach ordered step_runs and workflow name to a run map."
  [db run]
  (let [step-runs (next/execute! db
                                 (hsql/format {:select   :*
                                               :from     :project-workflow-step-runs
                                               :where    [:= :workflow-run-id (:id run)]
                                               :order-by [[:position :asc]]})
                                 {:builder-fn rs/as-unqualified-kebab-maps})
        wf-name   (when (:workflow-id run)
                    (:name (first (get-workflows-raw db {:id (:workflow-id run)}))))]
    (assoc run
           :steps (vec step-runs)
           :workflow-name wf-name)))

(defn get-workflow-runs
  "Return all runs for a project, newest first, each with step_runs."
  [db project-id]
  (->> (next/execute! db
                      (hsql/format {:select   :*
                                    :from     :project-workflow-runs
                                    :where    [:= :project-id project-id]
                                    :order-by [[:created-at :desc]]})
                      {:builder-fn rs/as-unqualified-kebab-maps})
       (mapv (partial enrich-run db))))

(defn get-workflow-run
  "Return a single run with its step_runs."
  [db run-id]
  (when-let [run (first (get-workflow-runs-raw db {:id run-id}))]
    (enrich-run db run)))

(defn create-workflow-run!
  "Create a run for a project. If workflow-id is provided, creates a pending
   step_run for each sequential step. Parallel and fan_in steps are created
   per-round when the loop executor starts — not upfront.
   config carries the policy block (scrutiny level, thresholds, etc.)."
  [db project-id workflow-id mode config]
  (next/with-transaction [tx db]
    (let [now (utils/now)
          run (first (rdbms/insert! tx
                                    :project-workflow-runs
                                    {:id           (utils/uuid)
                                     :project-id   project-id
                                     :workflow-id  workflow-id
                                     :status       "in_progress"
                                     :current-step 0
                                     :mode         (or mode "manual")
                                     :config       (or config {})
                                     :started-at   now
                                     :created-at   now}
                                    :ex-subtype :UnableToCreateWorkflowRun))]
      (when workflow-id
        (let [steps (next/execute! tx
                                   (hsql/format {:select   :*
                                                 :from     :workflow-steps
                                                 :where    [:= :workflow-id workflow-id]
                                                 :order-by [[:position :asc]]})
                                   {:builder-fn rs/as-unqualified-kebab-maps})
              ;; Only create step runs for sequential steps upfront.
              ;; Parallel and fan_in steps are created fresh per round.
              sequential-steps (filter #(= "sequential" (or (:execution-mode %) "sequential")) steps)]
          (when (seq sequential-steps)
            (rdbms/insert! tx
                           :project-workflow-step-runs
                           (mapv (fn [step]
                                   {:id              (utils/uuid)
                                    :workflow-run-id (:id run)
                                    :step-id         (:id step)
                                    :position        (:position step)
                                    :name            (:name step)
                                    :description     (:description step)
                                    :agent-type      (or (:agent-type step) "coach")
                                    :output-kind     (or (:output-kind step) "text")
                                    :execution-mode  "sequential"
                                    :is-custom       false
                                    :status          "pending"})
                                 sequential-steps)
                           :ex-subtype :UnableToCreateStepRun))))
      (enrich-run tx run))))

(defn update-workflow-run!
  "Update run fields (status, mode, current-step, completed-at)."
  [db run-id updates]
  (first (rdbms/update! db
                        :project-workflow-runs
                        (assoc updates :id run-id))))

(defn get-step-run
  "Return a single step_run by id."
  [db step-run-id]
  (first (get-step-runs-raw db {:id step-run-id})))

(defn update-step-run!
  "Update a step_run's status, output, started-at, completed-at."
  [db step-run-id updates]
  (first (rdbms/update! db
                        :project-workflow-step-runs
                        (assoc updates :id step-run-id))))

(defn create-custom-step-run!
  "Insert a new custom step_run at the given position in a run."
  [db workflow-run-id {:keys [name description agent-type position]}]
  (first (rdbms/insert! db
                        :project-workflow-step-runs
                        {:id              (utils/uuid)
                         :workflow-run-id workflow-run-id
                         :step-id         nil
                         :position        position
                         :name            name
                         :description     description
                         :agent-type      (or agent-type "coach")
                         :is-custom       true
                         :status          "pending"}
                        :ex-subtype :UnableToCreateStepRun)))

(defn create-refinement-step-run!
  "Insert an ad-hoc refine step_run associated with a round."
  [db workflow-run-id {:keys [name description agent-type round-id position]}]
  (first (rdbms/insert! db
                        :project-workflow-step-runs
                        {:id              (utils/uuid)
                         :workflow-run-id workflow-run-id
                         :step-id         nil
                         :round-id        round-id
                         :position        (or position 99)
                         :name            (or name "Advisor refinement")
                         :description     description
                         :agent-type      (or agent-type "coach")
                         :output-kind     "refine"
                         :execution-mode  "sequential"
                         :is-custom       true
                         :status          "pending"}
                        :ex-subtype :UnableToCreateStepRun)))

(defn next-custom-step-position
  "Return the next available position for a step_run in a run."
  [db workflow-run-id]
  (let [result (first (next/execute! db
                                     (hsql/format {:select [[[:coalesce [:max :position] -1] :max-pos]]
                                                   :from   :project-workflow-step-runs
                                                   :where  [:= :workflow-run-id workflow-run-id]})
                                     {:builder-fn rs/as-unqualified-kebab-maps}))]
    (inc (:max-pos result -1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workflow rounds
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-round!
  "Create a new in_progress round for a workflow run."
  [db workflow-run-id round-number]
  (first (rdbms/insert! db
                        :workflow-rounds
                        {:id              (utils/uuid)
                         :workflow-run-id workflow-run-id
                         :round-number    round-number
                         :status          "in_progress"
                         :started-at      (utils/now)}
                        :ex-subtype :UnableToCreateRound)))

(defn complete-round!
  "Mark a round as completed."
  [db round-id]
  (first (rdbms/update! db
                        :workflow-rounds
                        {:id           round-id
                         :status       "completed"
                         :completed-at (utils/now)})))

(defn get-round
  "Return a single round by its ID."
  [db round-id]
  (first (next/execute! db
                        (hsql/format {:select :*
                                      :from   :workflow-rounds
                                      :where  [:= :id round-id]})
                        {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-current-round
  "Return the in_progress round for a workflow run, or nil if none."
  [db workflow-run-id]
  (first (next/execute! db
                        (hsql/format {:select   :*
                                      :from     :workflow-rounds
                                      :where    [:and
                                                 [:= :workflow-run-id workflow-run-id]
                                                 [:= :status "in_progress"]]
                                      :order-by [[:round-number :desc]]
                                      :limit    1})
                        {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-all-rounds
  "Return all rounds for a run, ordered by round_number."
  [db workflow-run-id]
  (next/execute! db
                 (hsql/format {:select   :*
                                :from     :workflow-rounds
                                :where    [:= :workflow-run-id workflow-run-id]
                                :order-by [[:round-number :asc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn create-round-step-runs!
  "Create pending step runs for all parallel and fan_in steps in a workflow for a given round.
   These are the steps that execute within each loop iteration."
  [db workflow-run-id workflow-id round-id]
  (let [loop-steps (next/execute! db
                                  (hsql/format {:select   :*
                                                :from     :workflow-steps
                                                :where    [:and
                                                           [:= :workflow-id workflow-id]
                                                           [:in :execution-mode ["parallel" "fan_in"]]]
                                                :order-by [[:position :asc]]})
                                  {:builder-fn rs/as-unqualified-kebab-maps})]
    (when (seq loop-steps)
      (rdbms/insert! db
                     :project-workflow-step-runs
                     (mapv (fn [step]
                             {:id              (utils/uuid)
                              :workflow-run-id workflow-run-id
                              :step-id         (:id step)
                              :round-id        round-id
                              :position        (:position step)
                              :name            (:name step)
                              :description     (:description step)
                              :agent-type      (or (:agent-type step) "coach")
                              :output-kind     (or (:output-kind step) "text")
                              :execution-mode  (or (:execution-mode step) "sequential")
                              :loop-until      (:loop-until step)
                              :is-custom       false
                              :status          "pending"})
                           loop-steps)
                     :ex-subtype :UnableToCreateStepRun))))

(defn get-step-runs-by-round-and-mode
  "Return step runs for a given round filtered by execution_mode and status.
   statuses defaults to #{\"pending\"} to avoid re-running completed steps.
   Pass nil to return all statuses."
  ([db workflow-run-id round-id execution-mode]
   (get-step-runs-by-round-and-mode db workflow-run-id round-id execution-mode #{"pending"}))
  ([db workflow-run-id round-id execution-mode statuses]
   (next/execute! db
                  (hsql/format {:select   :*
                                 :from     :project-workflow-step-runs
                                 :where    (cond-> [:and
                                                    [:= :workflow-run-id workflow-run-id]
                                                    [:= :round-id round-id]
                                                    [:= :execution-mode execution-mode]]
                                             statuses (conj [:in :status (vec statuses)]))
                                 :order-by [[:position :asc]]})
                  {:builder-fn rs/as-unqualified-kebab-maps})))

(defn workflow-has-loop-steps?
  "Returns true if the workflow has any parallel or fan_in steps."
  [db workflow-id]
  (boolean (seq (next/execute! db
                               (hsql/format {:select   :*
                                             :from     :workflow-steps
                                             :where    [:and
                                                        [:= :workflow-id workflow-id]
                                                        [:in :execution-mode ["parallel" "fan_in"]]]
                                             :limit    1})
                               {:builder-fn rs/as-unqualified-kebab-maps}))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Judge records
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-judge-record!
  "Persist the complete input snapshot and output for a team-lead decision.
   This table is append-only — every decision is fully reconstructable from it."
  [db {:keys [step-run-id round-id brief-version score-snapshot trajectory
              delta-table policy decision]}]
  (first (rdbms/insert! db
                        :workflow-judge-records
                        {:id             (utils/uuid)
                         :step-run-id    step-run-id
                         :round-id       round-id
                         :brief-version  brief-version
                         :score-snapshot score-snapshot
                         :trajectory     trajectory
                         :delta-table    delta-table
                         :policy         policy
                         :decision       decision
                         :created-at     (utils/now)}
                        :ex-subtype :UnableToCreateJudgeRecord)))

(defn get-latest-judge-record
  "Return the most recent judge record for a workflow run, or nil.
   Joins through workflow_rounds to filter by run."
  [db workflow-run-id]
  (first (next/execute! db
                        (hsql/format {:select   [[:wjr/id :id]
                                                 [:wjr/round-id :round-id]
                                                 [:wjr/brief-version :brief-version]
                                                 [:wjr/decision :decision]
                                                 [:wjr/created-at :created-at]]
                                      :from     [[:workflow-judge-records :wjr]]
                                      :join     [[:workflow-rounds :wr] [:= :wr/id :wjr/round-id]]
                                      :where    [:= :wr/workflow-run-id workflow-run-id]
                                      :order-by [[:wjr/created-at :desc]]
                                      :limit    1})
                        {:builder-fn rs/as-unqualified-kebab-maps})))
