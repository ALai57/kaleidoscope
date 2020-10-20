(ns andrewslai.clj.api.users
  (:require [andrewslai.clj.entities.user :as user-entity]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [file->bytes validate]]
            [clojure.java.data :as j]
            [clojure.spec.alpha :as s]
            [crypto.password.bcrypt :as password])
  (:import (com.nulabinc.zxcvbn Zxcvbn)))

(defn password-strength [password]
  (-> Zxcvbn
      new
      (.measure password)
      j/from-java
      :score))

(s/def :user/password (s/and string? (fn sufficient-strength? [password]
                                       (<= 4 (password-strength password)))))

;; TODO: Add spec into the API
(defn login
  "Verify credentials and return the user ID if successful"
  [database {:keys [username password] :as credentials}]
  (if-let [id (:id (users/get-user database username))]
    (let [{password-from-db :hashed_password} (users/get-login database id)]
      (and password-from-db
           (password/check password password-from-db)
           id))))

(defn get-user
  [database username]
  (users/get-user database username))

(defn delete-user!
  [database credentials]
  (when-let [id (login database credentials)]
    ;; TODO: Wrap in transaction
    (users/delete-login! database id)
    (users/delete-user! database id)))

(defn update-user!
  [database username user-update]
  (users/update-user! database username user-update))

(def default-role 2)
(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))

(defn register-user!
  [database user password]
  (validate :user/password password :IllegalArgumentException)
  (let [user-id (java.util.UUID/randomUUID)
        full-user (assoc user
                         :id user-id
                         :role_id default-role
                         :avatar (or (:avatar user) default-avatar))]
    (user-entity/create-user-profile! database full-user)
    (users/create-login! database user-id password)
    full-user))
