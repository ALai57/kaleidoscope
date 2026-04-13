(ns kaleidoscope.persistence.tasks
  (:require [honey.sql :as hsql]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Internal helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private get-tasks-raw
  (rdbms/make-finder :project-tasks))

(defn- next-position
  "Return max(position) + 1 for a project's task list, or 0 if empty."
  [db project-id]
  (let [row (first (next/execute! db
                                  (hsql/format {:select [[[:coalesce [:max :position] -1] :max-pos]]
                                                :from   :project-tasks
                                                :where  [:= :project-id project-id]})
                                  {:builder-fn rs/as-unqualified-kebab-maps}))]
    (inc (:max-pos row -1))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Queries
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn list-tasks
  "Return all tasks for a project ordered by position ASC.
   Optionally pass a status string to filter."
  ([db project-id]
   (next/execute! db
                  (hsql/format {:select   :*
                                :from     :project-tasks
                                :where    [:= :project-id project-id]
                                :order-by [[:position :asc]]})
                  {:builder-fn rs/as-unqualified-kebab-maps}))
  ([db project-id status]
   (next/execute! db
                  (hsql/format {:select   :*
                                :from     :project-tasks
                                :where    [:and
                                           [:= :project-id project-id]
                                           [:= :status status]]
                                :order-by [[:position :asc]]})
                  {:builder-fn rs/as-unqualified-kebab-maps})))

(defn get-task
  "Return a single task by id."
  [db task-id]
  (first (get-tasks-raw db {:id task-id})))

(defn create-task!
  "Insert a new task. If position is omitted, appended at end of list."
  [db {:keys [project-id user-id title description task-type estimated-minutes
              generation-run-id workflow-step-run-id position]}]
  (let [pos (or position (next-position db project-id))
        now (utils/now)]
    (first (rdbms/insert! db
                          :project-tasks
                          {:id                   (utils/uuid)
                           :project-id           project-id
                           :user-id              user-id
                           :title                title
                           :description          description
                           :task-type            (or task-type "action")
                           :status               "pending"
                           :position             pos
                           :estimated-minutes    estimated-minutes
                           :generation-run-id    generation-run-id
                           :workflow-step-run-id workflow-step-run-id
                           :created-at           now
                           :updated-at           now}
                          :ex-subtype :UnableToCreateTask))))

(defn update-task!
  "Partial update on a task row. Returns the updated task."
  [db task-id updates]
  (first (rdbms/update! db :project-tasks (assoc updates :id task-id))))

(defn delete-task!
  "Hard delete a task by id."
  [db task-id]
  (rdbms/delete! db :project-tasks task-id :ex-subtype :UnableToDeleteTask))

(defn bulk-reorder!
  "Update positions for multiple tasks in a single transaction.
   positions is a seq of {:id uuid :position int}."
  [db positions]
  (next/with-transaction [tx db]
    (doseq [{:keys [id position]} positions]
      (rdbms/update! tx :project-tasks {:id id :position position}))))

(defn bulk-create-tasks!
  "Insert multiple tasks in a transaction, assigning sequential positions
   starting at the current max + 1.
   opts may contain :generation-run-id and :workflow-step-run-id."
  [db project-id user-id tasks {:keys [generation-run-id workflow-step-run-id]}]
  (next/with-transaction [tx db]
    (let [start-pos (next-position tx project-id)
          now       (utils/now)]
      (vec
       (map-indexed
        (fn [i {:keys [title description task-type estimated-minutes]}]
          (first (rdbms/insert! tx
                                :project-tasks
                                {:id                   (utils/uuid)
                                 :project-id           project-id
                                 :user-id              user-id
                                 :title                title
                                 :description          description
                                 :task-type            (or task-type "action")
                                 :status               "pending"
                                 :position             (+ start-pos i)
                                 :estimated-minutes    estimated-minutes
                                 :generation-run-id    generation-run-id
                                 :workflow-step-run-id workflow-step-run-id
                                 :created-at           now
                                 :updated-at           now}
                                :ex-subtype :UnableToCreateTask)))
        tasks)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Generation runs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn create-generation-run!
  "Create a task generation run audit record."
  [db project-id user-id]
  (first (rdbms/insert! db
                        :project-task-generation-runs
                        {:id         (utils/uuid)
                         :project-id project-id
                         :user-id    user-id
                         :created-at (utils/now)}
                        :ex-subtype :UnableToCreateGenerationRun)))

(defn list-generation-runs
  "Return all generation runs for a project, newest first."
  [db project-id]
  (next/execute! db
                 (hsql/format {:select   :*
                               :from     :project-task-generation-runs
                               :where    [:= :project-id project-id]
                               :order-by [[:created-at :desc]]})
                 {:builder-fn rs/as-unqualified-kebab-maps}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Status summary
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn get-task-status
  "Return {:pending-count N :total-count N} for a project."
  [db project-id]
  (let [row (first
             (next/execute! db
                            (hsql/format
                             {:select [[[:count :*] :total-count]
                                       [[:sum [:case [:= :status "pending"] 1 :else 0]] :pending-count]]
                              :from   :project-tasks
                              :where  [:= :project-id project-id]})
                            {:builder-fn rs/as-unqualified-kebab-maps}))]
    {:pending-count (or (:pending-count row) 0)
     :total-count   (or (:total-count row) 0)}))
