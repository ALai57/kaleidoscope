(ns andrewslai.clj.api.users
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.auth.crypto :refer [encrypt check make-encryption]]
            [andrewslai.clj.utils :refer [file->bytes]]
            [crypto.password.bcrypt :as password]
            [clojure.data.codec.base64 :as b64]
            [slingshot.slingshot :refer [try+ throw+]]
            [taoensso.timbre :as log]))

;; TODO: Add spec into the API
(defn login
  "Verify credentials and return the user ID if successful"
  [Persistence {:keys [username password] :as credentials}]
  (if-let [id (:id (users/get-user Persistence username))]
    (let [true-password (users/get-password Persistence id)]
      (and true-password
           (password/check password true-password)
           id))))

(defn get-user
  "Retrieves a user's profile"
  ;; TODO: Add a presenter in here too
  [Persistence username]
  (users/get-user Persistence username))

(defn delete-user!
  "Verifies that the user is authorized to perform the operation, then
  deletes the user"
  [db credentials]
  (when-let [id (login db credentials)]
    ;; TODO: Wrap in transaction
    (users/delete-login! db id)
    (users/delete-user! db id)))

(defn update-user!
  "Updates a particular user with an update payload"
  [db username user-update]
  (users/update-user! db username user-update))

(def default-role 2)
(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))

(defn register-user!
  "Create a user with associated password"
  [this user password]
  (let [user-id (java.util.UUID/randomUUID)
        full-user (assoc user
                         :id user-id
                         :role_id default-role
                         :avatar (or (:avatar user) default-avatar))]
    (users/create-user! this full-user)
    (users/create-login! this user-id password)
    full-user))
