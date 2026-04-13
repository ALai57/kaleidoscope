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
                              (fn [i {:keys [name description position agent-type output-kind]}]
                                {:id          (utils/uuid)
                                 :workflow-id (:id wf)
                                 :position    (or position i)
                                 :name        name
                                 :description description
                                 :agent-type  (or agent-type "coach")
                                 :output-kind (or output-kind "text")
                                 :created-at  now
                                 :updated-at  now})
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
                                (fn [i {:keys [name description position agent-type output-kind]}]
                                  {:id          (utils/uuid)
                                   :workflow-id workflow-id
                                   :position    (or position i)
                                   :name        name
                                   :description description
                                   :agent-type  (or agent-type "coach")
                                   :output-kind (or output-kind "text")
                                   :created-at  now
                                   :updated-at  now})
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
  "Create a run for a project. If workflow-id is provided, also creates
   a pending step_run for each workflow step."
  [db project-id workflow-id mode]
  (next/with-transaction [tx db]
    (let [now (utils/now)
          run (first (rdbms/insert! tx
                                    :project-workflow-runs
                                    {:id          (utils/uuid)
                                     :project-id  project-id
                                     :workflow-id workflow-id
                                     :status      "in_progress"
                                     :current-step 0
                                     :mode        (or mode "manual")
                                     :started-at  now
                                     :created-at  now}
                                    :ex-subtype :UnableToCreateWorkflowRun))]
      (when workflow-id
        (let [steps (next/execute! tx
                                   (hsql/format {:select   :*
                                                 :from     :workflow-steps
                                                 :where    [:= :workflow-id workflow-id]
                                                 :order-by [[:position :asc]]})
                                   {:builder-fn rs/as-unqualified-kebab-maps})]
          (when (seq steps)
            (rdbms/insert! tx
                           :project-workflow-step-runs
                           (vec (map-indexed
                                  (fn [i step]
                                    {:id              (utils/uuid)
                                     :workflow-run-id (:id run)
                                     :step-id         (:id step)
                                     :position        i
                                     :name            (:name step)
                                     :description     (:description step)
                                     :agent-type      (or (:agent-type step) "coach")
                                     :output-kind     (or (:output-kind step) "text")
                                     :is-custom       false
                                     :status          "pending"})
                                  steps))
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

(defn next-custom-step-position
  "Return the next available position for a step_run in a run."
  [db workflow-run-id]
  (let [result (first (next/execute! db
                                     (hsql/format {:select [[[:coalesce [:max :position] -1] :max-pos]]
                                                   :from   :project-workflow-step-runs
                                                   :where  [:= :workflow-run-id workflow-run-id]})
                                     {:builder-fn rs/as-unqualified-kebab-maps}))]
    (inc (:max-pos result -1))))
