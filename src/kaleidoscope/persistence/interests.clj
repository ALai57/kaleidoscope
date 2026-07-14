(ns kaleidoscope.persistence.interests
  (:require [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]))

(def ^:private get-interests-raw
  (rdbms/make-finder :interests))

(defn get-interests
  "Return all interests for a user."
  [db user-id]
  (get-interests-raw db {:user-id user-id}))

(defn get-interest
  "Return a single interest, checking user ownership."
  [db interest-id user-id]
  (first (get-interests-raw db {:id interest-id :user-id user-id})))

(defn get-interest-by-project-id
  "Return the interest backed by a project, or nil. Unscoped — used internally
  by the workflow executor, which receives the project after the caller has
  already verified ownership."
  [db project-id]
  (first (get-interests-raw db {:project-id project-id})))

(defn- interest-title
  [intent]
  (let [prefix (str "Interest: " intent)]
    (if (> (count prefix) 120) (subs prefix 0 120) prefix)))

(defn create-interest!
  "Create an interest and its backing project in one transaction. The backing
  project is what curation workflow runs attach to (Interest ≈ Project)."
  [db {:keys [user-id intent taste-profile]}]
  (next/with-transaction [tx db]
    (let [now     (utils/now)
          project (projects-persistence/create-project! tx {:user-id     user-id
                                                            :title       (interest-title intent)
                                                            :description intent
                                                            :status      "interest"})]
      (first (rdbms/insert! tx
                            :interests
                            {:id            (utils/uuid)
                             :user-id       user-id
                             :project-id    (:id project)
                             :intent        intent
                             :taste-profile (or taste-profile {})
                             :created-at    now
                             :updated-at    now}
                            :ex-subtype :UnableToCreateInterest)))))

(defn update-interest!
  "Update an interest, scoped to user-id. Returns nil if not found or not
  owned — the WHERE clause enforces that, not a preceding check. Only
  intent/taste-profile are settable: the updates map is destructured, not
  passed through, so a caller can't smuggle in :user-id or :project-id."
  [db interest-id user-id {:keys [intent taste-profile]}]
  (first (rdbms/scoped-update! db
                               :interests
                               {:id interest-id :user-id user-id}
                               (cond-> {:updated-at (utils/now)}
                                 intent               (assoc :intent intent)
                                 (some? taste-profile) (assoc :taste-profile taste-profile)))))

(defn delete-interest!
  "Delete an interest by deleting its backing project row — the interests row,
  its recommendations, and any curation runs all cascade from it. Returns the
  deleted interest, or nil if not found or not owned."
  [db interest-id user-id]
  (when-let [interest (get-interest db interest-id user-id)]
    (rdbms/scoped-delete! db :projects {:id (:project-id interest) :user-id user-id}
                          :ex-subtype :UnableToDeleteInterest)
    interest))
