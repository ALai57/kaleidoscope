(ns andrewslai.clj.persistence.users
  (:require [andrewslai.clj.auth.crypto :refer [encrypt check make-encryption]]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [clojure.java.data :as j]
            [clojure.java.jdbc :as sql]
            [clojure.spec.alpha :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (com.nulabinc.zxcvbn Zxcvbn)))

;; RESOURCES FOR AUTHENTICATION RELATED TOPICS
;; https://stackoverflow.com/questions/6832445/how-can-bcrypt-have-built-in-salts
;; https://funcool.github.io/buddy-auth/latest/#signed-jwt

;; TODO: Log client out after 30 mins

;; TODO: Basic endpoint for adding a new username
;; TODO: Verify that username doesn't already exist
;; TODO: If username exists, throw a non-200


(defprotocol UserPersistence
  (register-user! [_ user password])
  (create-user! [_ user])
  (create-login! [_ user-id password])
  (get-user [_ username])
  (get-user-by-id [_ user-id])
  (get-users [_])
  (update-user [_ username update-payload])
  (get-password [_ user-id])
  (delete-user! [_ credentials])
  (verify-credentials [_ credentials])
  (login [_ credentials]))

(def default-role 2)
(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(defn email? [s] (when (string? s)
                   (re-matches email-regex s)))

(defn alphanumeric? [s]
  (some? (re-matches #"^[a-zA-Z0-9-_]+$" s)))

(s/def ::id uuid?)
(s/def ::first_name (s/and string? #(< 0 (count %))))
(s/def ::last_name (s/and string? #(< 0 (count %))))
(s/def ::username (s/and string? #(< 0 (count %)) alphanumeric?))
(s/def ::email email?)
(s/def ::avatar bytes?)
(s/def ::role_id (s/and int? pos?))
(s/def ::user (s/keys :req-un [::avatar
                               ::first_name
                               ::last_name
                               ::username
                               ::email]
                      :opt [::role_id
                            ::id]))


(defn password-strength [password]
  (-> Zxcvbn
      new
      (.measure password)
      j/from-java
      (select-keys [:score :feedback])))

(defn sufficient-strength? [{:keys [score]}]
  (<= 4 score))

(s/def ::password-strength sufficient-strength?)

(comment
  (-> Zxcvbn
      new
      (.measure "password")
      j/from-java
      (select-keys [:score :feedback]))
  )

(defn validate [type data]
  (if (s/valid? type data)
    true
    (throw+
      (let [reason (s/explain-str type data)]
        {:type ::IllegalArgumentException
         :subtype type
         :message {:data data
                   :reason reason
                   :feedback (or (:feedback data)
                                 reason)}}))))

(defn- -create-user! [this user]
  (rdbms/insert! (:database this) "users" user))

(defn- -create-login! [this id encrypted-password]
  (rdbms/insert! (:database this) "logins"
                 {:id id, :hashed_password encrypted-password}))

;;https://www.donedone.com/building-the-optimal-user-database-model-for-your-application/
(defn- -register-user! [this {:keys [role_id] :as user} password]
  (try+
    (validate ::user user)
    (validate ::password-strength (password-strength password))
    (let [user-id (java.util.UUID/randomUUID)
          full-user (-> user
                        (assoc :id user-id)
                        (assoc :role_id (or role_id default-role)))]
      (create-user! this full-user)
      (create-login! this user-id (encrypt (make-encryption) password))
      full-user)
    (catch org.postgresql.util.PSQLException e
      (throw+ {:type ::PSQLException
               :message {:data (select-keys user [:username :email])
                         :reason (.getMessage e)
                         :feedback "Try a different username and/or email"}}))))

(defn -get-users [this]
  (rdbms/hselect (:database this) {:select [:*]
                                   :from [:users]}))

(defn -get-user [this username]
  (first (rdbms/hselect (:database this) {:select [:*]
                                          :from [:users]
                                          :where [:= :users/username username]})))

(defn -get-user-by-id [this user-id]
  (first (rdbms/hselect (:database this) {:select [:*]
                                          :from [:users]
                                          :where [:= :users/id user-id]})))

(defn -update-user! [this username update-payload]
  (let [n-updates (first (rdbms/update! (:database this)
                                        "users"
                                        update-payload
                                        {:username username}))]
    (if (= 1 n-updates)
      update-payload)))

(defn -get-password [this user-id]
  (:hashed_password (first (rdbms/hselect (:database this)
                                          {:select [:*]
                                           :from [:logins]
                                           :where [:= :users/id user-id]}))))

(defn -verify-credentials [this {:keys [username password]}]
  (let [{:keys [id]} (get-user this username)]
    (and id
         (get-password this id)
         (check (make-encryption)
                password
                (get-password this id)))))

(defn -login [this {:keys [username password] :as credentials}]
  (when (verify-credentials this credentials)
    (:id (get-user this username))))

(defn -delete-user! [this {:keys [username] :as credentials}]
  (when (verify-credentials this credentials)
    (let [{:keys [id]} (get-user this username)]
      (rdbms/delete! (:database this) "logins" {:id id})
      (first (rdbms/delete! (:database this) "users" {:username username})))))

;; Can this be refactored so that the record takes an implementation as an arg?
;; For example, you call ->UserDatabase, and instead of conn, give it another
;;  object that has implemented protocol RelationalDatabase.
;; Then, the only difference between Postgres and alternate implmenetaton is how
;;  to do CRUD on the DB: e.g. how to update an atom vs to update a RDBMS
(defrecord UserDatabase [database]
  UserPersistence
  (register-user! [this user password]
    (-register-user! this user password))
  (create-user! [this user]
    (-create-user! this user))
  (create-login! [this user-id password]
    (-create-login! this user-id password))
  (get-users [this]
    (-get-users this))
  (get-user [this username]
    (-get-user this username))
  (get-user-by-id [this user-id]
    (-get-user-by-id this user-id))
  (update-user [this username update-payload]
    (-update-user! this username update-payload))
  (get-password [this user-id]
    (-get-password this user-id))
  (delete-user! [this credentials]
    (-delete-user! this credentials))
  (verify-credentials [this credentials]
    (-verify-credentials this credentials))
  (login [this credentials]
    (-login this credentials)))

(defn wrap-user [handler]
  (fn [{user-id :identity components :components :as req}]
    (if (and user-id (:user components))
      (handler (assoc req :user (get-user-by-id (:user components) user-id)))
      (handler (assoc req :user nil)))))

(comment
  (->UserDatabase2 (rdbms/->Postgres postgres/pg-db))

  (register-user! (->UserDatabase postgres/pg-db)
                  {:username "fantasmita"
                   :email "littleghost@andrewlai.com"
                   :first_name "my"
                   :last_name "user"
                   :password "password"})

  (get-users (->UserDatabase postgres/pg-db))
  (get-user (->UserDatabase postgres/pg-db) "testuser")

  (get-user (->UserDatabase postgres/pg-db) "andrewslai_admin")

  (update-user (->UserDatabase postgres/pg-db)
               "testuser"
               {:first_name "just_updated1"
                :email "just_updated1@andrewslai.com"})

  (update-user (->UserDatabase postgres/pg-db)
               "testuser"
               {:avatar (file->bytes
                          (java.io.File. "/home/alai/dev/andrewslai/resources/avatars/smiley_emoji.png"))})

  (login (->UserDatabase postgres/pg-db) {:username "testuser"
                                          :password "password"})
  )

(defn get-avatar []
  (first (sql/query postgres/pg-db ["SELECT avatar from users;"])))

(comment
  ;;https://mysql.tutorials24x7.com/blog/guide-to-design-database-for-rbac-in-mysql
  (sql/db-do-commands pg-db [(slurp "./scripts/db/setup_rbac/setup_users.sql")
                             (slurp "./scripts/db/setup_rbac/setup_logins.sql")
                             (slurp "./scripts/db/setup_rbac/setup_roles.sql")
                             (slurp "./scripts/db/setup_rbac/setup_permissions.sql")
                             (slurp "./scripts/db/setup_rbac/setup_roles_permissions.sql")
                             ])

  (sql/db-do-commands pg-db [(slurp "./scripts/db/setup_rbac/delete_logins.sql")
                             (slurp "./scripts/db/setup_rbac/delete_users.sql")
                             (slurp "./scripts/db/setup_rbac/delete_roles_permissions.sql")
                             (slurp "./scripts/db/setup_rbac/delete_roles.sql")
                             (slurp "./scripts/db/setup_rbac/delete_permissions.sql")])

  (sql/db-do-commands pg-db [(slurp "./scripts/db/setup_rbac/insert_roles.sql")
                             (slurp "./scripts/db/setup_rbac/insert_permissions.sql")])

  (sql/query pg-db ["SELECT * FROM users"])
  (sql/query pg-db ["SELECT * FROM logins"])
  (sql/query pg-db ["SELECT * FROM roles"])
  (sql/query pg-db ["SELECT * FROM permissions"])

  (defn file->bytes [file]
    (with-open [xin (clojure.java.io/input-stream file)
                xout (java.io.ByteArrayOutputStream.)]
      (clojure.java.io/copy xin xout)
      (.toByteArray xout)))

  (file->bytes (clojure.java.io/resource "avatars/happy_emoji.jpg"))

  (sql/update! postgres/pg-db
               "users"
               {:avatar (file->bytes
                          (java.io.File. "/home/alai/dev/andrewslai/resources/avatars/smiley_emoji.png"))}
               ["username = ?" "testuser"])



  (create-user! {:username "andrewlai"
                 :email "andrewlai@andrewlai.com"
                 :first_name "andrew"
                 :last_name "lai"
                 :password "mypassword"})
  )
