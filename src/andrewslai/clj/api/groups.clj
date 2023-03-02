(ns andrewslai.clj.api.groups
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.utils.core :as utils]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log]))

(defn get-id
  [group]
  (:id group))

(defn get-owner
  [group]
  (:owner-id group))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-groups
  (rdbms/make-finder :groups))

(defn create-group!
  [database {:keys [id] :as group}]
  (let [now (utils/now)]
    (rdbms/insert! database
                   :groups     (assoc group
                                      :created-at  now
                                      :modified-at now
                                      :id          (or id (utils/uuid)))
                   :ex-subtype :UnableToCreateGroup)))

(defn owns?
  [database requester-id group-id]
  (= requester-id (get-owner (first (get-groups database {:id group-id})))))

(defn delete-group!
  "Only allow a user to delete a group if they are the owner.
  The `user-id` is the identity of the user requesting the operation."
  [database requester-id group-id]
  (if (owns? database requester-id group-id)
    (rdbms/delete! database
                   :groups     group-id
                   :ex-subtype :UnableToDeleteGroup)
    (log/warnf "User %s does not have permissions to delete the group %s" requester-id group-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos in albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-group-memberships
  (rdbms/make-finder :user-group-memberships))

(defn add-users-to-group!
  [database requester-id group-id user-ids]
  (if (owns? database requester-id group-id)
    (let [now-time    (utils/now)
          memberships (vec (for [user-id (if (seq? user-ids) user-ids [user-ids])]
                             {:id         (utils/uuid)
                              :user-id    user-id
                              :group-id   group-id
                              :created-at now-time}))]
      (vec (rdbms/insert! database
                          :user-group-memberships memberships
                          :ex-subtype             :UnableToAddUserToGroup)))
    (log/warnf "User %s does not have permissions to add users to group %s" requester-id group-id)))

(defn remove-user-from-group!
  [database requester-id group-id user-group-membership-id]
  (if (owns? database requester-id group-id)
    (rdbms/delete! database
                   :user-group-memberships user-group-membership-id
                   :ex-subtype :UnableToDeleteUserFromGroup)
    (log/warnf "User %s does not have permissions to delete users from group %s" requester-id group-id)))

(comment


  )
