(ns andrewslai.clj.api.users
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [file->bytes]]
            [clojure.data.codec.base64 :as b64]
            [slingshot.slingshot :refer [try+ throw+]]
            [taoensso.timbre :as log]))

(defn login
  "Verify credentials and return the user ID if successful"
  [Persistence {:keys [username] :as credentials}]
  (when (users/verify-credentials Persistence credentials)
    (:id (users/get-user Persistence username))))

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
    (users/delete-user! db id)))

(defn update-user!
  "Updates a particular user with an update payload"
  [db username user-update]
  (users/update-user! db username user-update))

(def default-role 2)
(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))

(defn register-user! [this user password]
  (try+
   (let [user-id (java.util.UUID/randomUUID)
         full-user (assoc user
                          :id user-id
                          :role_id default-role
                          :avatar (or (:avatar user) default-avatar))]
     ;; TODO: Wrap in db transaction
     ;; TODO: Wrap each of these protocol methods with catch PSQL exception
     ;;        and rethrow as Persistence exception. Should not be tied to PSQL
     (users/create-user! this full-user)
     (users/create-login! this user-id password)
     full-user)
   (catch org.postgresql.util.PSQLException e
     (throw+ {:type :PSQLException
              :subtype ::UnableToCreateUser
              :message {:data (select-keys user [:username :email])
                        :reason (.getMessage e)
                        :feedback "Try a different username and/or email"}}))))
