(ns andrewslai.clj.api.users
  (:require [andrewslai.clj.entities.user :as user]
            [andrewslai.clj.utils :refer [file->bytes validate]]
            [clojure.java.data :as j]
            [clojure.spec.alpha :as s]
            [crypto.password.bcrypt :as password]
            [taoensso.timbre :as log])
  (:import com.nulabinc.zxcvbn.Zxcvbn))

(defn password-strength [password]
  (-> Zxcvbn
      new
      (.measure password)
      j/from-java
      :score))

(s/def :andrewslai.user/password
  (s/and string? (fn sufficient-strength? [password]
                   (<= 4 (password-strength password)))))

(defn verify-credentials
  "Verify credentials and return the user ID if successful"
  [database {:keys [username password] :as credentials}]
  (if-let [id (:id (user/get-user-profile database username))]
    (let [{password-from-db :hashed_password} (user/get-user-login database id)]
      (and password-from-db
           (password/check password password-from-db)
           id))))

(defn login
  "Verify credentials and return the user if successful"
  [database {:keys [username password] :as credentials}]
  (if-let [user-id (verify-credentials database credentials)]
    (do (log/info "Authenticated login!")
        (-> database
            (user/get-user-profile-by-id user-id)
            (assoc :avatar_url (format "users/%s/avatar" username))))
    (log/info "Invalid username/password")))

(defn get-user
  [database username]
  (user/get-user-profile database username))

(defn delete-user!
  [database credentials]
  (when-let [{:keys [id]} (login database credentials)]
    ;; TODO: Wrap in transaction
    (user/delete-user-login! database id)
    (user/delete-user-profile! database id)))

;; TODO: rename to update-user-profile!
(defn update-user!
  [database username profile-update]
  (user/update-user-profile! database username profile-update))

(def default-role 2)
(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))

(defn register-user!
  ([database user password]
   (register-user! database user password
                   (or (some-> (System/getenv "ANDREWSLAI_ENCRYPTION_WORK_FACTOR")
                               int)
                       12)))
  ([database user password work-factor]
   (validate :andrewslai.user/password password :IllegalArgumentException)
   (let [user-id (java.util.UUID/randomUUID)
         full-user (assoc user
                          :id user-id
                          :role_id default-role
                          :avatar (or (:avatar user) default-avatar))]
     (user/create-user-profile! database full-user)
     (user/create-user-login! database
                              user-id
                              (password/encrypt password work-factor))
     full-user)))

(comment
  ;; Checking encryption/decryption
  (let [encrypted-password (encrypt (make-encryption) "foobar")]
    (is (check (make-encryption) "foobar" encrypted-password))))
