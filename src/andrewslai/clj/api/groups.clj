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
                   :ex-subtype :UnableToCreateAlbum)))

(defn owns?
  [database user-id group-id]
  (= user-id (get-owner (first (get-groups database {:id group-id})))))

(defn delete-group!
  "Only allow a user to delete a group if they are the owner.
  The `user-id` is the identity of the user requesting the operation."
  [database user-id group-id]
  (if (owns? database user-id group-id)
    (rdbms/delete! database
                   :groups     group-id
                   :ex-subtype :UnableToDeleteGroup)
    (log/warnf "User %s does not have permissions to delete the group %s" user-id group-id)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos in albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-group-memberships
  (rdbms/make-finder :user-group-memberships))

(defn add-users-to-group!
  [database group-id user-ids]
  (let [now-time    (utils/now)
        memberships (vec (for [user-id (if (seq? user-ids) user-ids [user-ids])]
                           {:id         (utils/uuid)
                            :user-id    user-id
                            :group-id   group-id
                            :created-at now-time}))]
    (vec (rdbms/insert! database
                        :user-group-memberships memberships
                        :ex-subtype             :UnableToAddUserToGroup))))

(defn remove-user-from-group!
  [database user-group-membership-id]
  (rdbms/delete! database
                 :user-group-memberships user-group-membership-id
                 :ex-subtype :UnableToDeletePhotoFromAlbum))

(comment


  )
