(ns andrewslai.clj.api.groups
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.utils.core :as utils]
            [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Groups
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-groups
  (rdbms/make-finder :groups))

(defn create-group!
  [database group]
  (let [now (utils/now)]
    (rdbms/insert! database
                   :groups     (assoc group
                                      :created-at  now
                                      :modified-at now)
                   :ex-subtype :UnableToCreateAlbum)))

(defn delete-group!
  [database group-id]
  (rdbms/delete! database
                 :groups     group-id
                 :ex-subtype :UnableToDeleteGroup))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos in albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-group-memberships
  (rdbms/make-finder :user-group-memberships))

(defn add-users-to-group!
  [database group-id user-ids]
  (let [now-time    (utils/now)
        memberships (vec (for [user-id (if (seq? user-ids) user-ids [user-ids])]
                           {:user-id    user-id
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
