(ns kaleidoscope.persistence.workspace-roots
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]))

(def ^:private get-workspace-roots-raw
  (rdbms/make-finder :user-workspace-roots))

(defn get-workspace-roots
  "Return all workspace roots for a user, ordered by created_at."
  [db user-id]
  (get-workspace-roots-raw db {:user-id user-id}))

(defn add-workspace-root!
  "Register a new workspace root path for a user. Returns the created row,
   or nil if the path is already registered (UNIQUE constraint)."
  [db user-id path label]
  (try
    (first (rdbms/insert! db
                          :user-workspace-roots
                          {:id         (utils/uuid)
                           :user-id    user-id
                           :path       path
                           :label      label
                           :created-at (utils/now)}
                          :ex-subtype :UnableToAddWorkspaceRoot))
    (catch Exception _
      nil)))

(defn delete-workspace-root!
  "Delete a workspace root by id. Checks user_id to prevent cross-user deletion.
   Returns the deleted row, or nil if not found."
  [db workspace-root-id user-id]
  (when-let [root (first (get-workspace-roots-raw db {:id workspace-root-id :user-id user-id}))]
    (first (rdbms/delete! db :user-workspace-roots (:id root)
                          :ex-subtype :UnableToDeleteWorkspaceRoot))
    root))
