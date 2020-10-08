(ns andrewslai.clj.api.users
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [file->bytes]]
            [crypto.password.bcrypt :as password]))

;; TODO: Add spec into the API
(defn login
  "Verify credentials and return the user ID if successful"
  [Persistence database {:keys [username password] :as credentials}]
  (if-let [id (:id (users/get-user database username))]
    (let [password-from-db (users/get-password Persistence id)]
      (and password-from-db
           (password/check password password-from-db)
           id))))

(defn get-user
  "Retrieves a user's profile"
  [database username]
  (users/get-user database username))

(defn delete-user!
  "Verifies that the user is authorized to perform the operation, then
  deletes the user"
  [Persistence database credentials]
  (when-let [id (login Persistence database credentials)]
    ;; TODO: Wrap in transaction
    (users/delete-login! Persistence id)
    (users/delete-user! Persistence id)))

(defn update-user!
  "Updates a particular user with an update payload"
  [Persistence username user-update]
  (users/update-user! Persistence username user-update))

(def default-role 2)
(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))

(defn register-user!
  "Create a user with associated password"
  [database user password]
  (let [user-id (java.util.UUID/randomUUID)
        full-user (assoc user
                         :id user-id
                         :role_id default-role
                         :avatar (or (:avatar user) default-avatar))]
    (users/create-user! database full-user)
    (users/create-login! database user-id password)
    full-user))
