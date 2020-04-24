(ns andrewslai.clj.persistence.users
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [clojure.java.jdbc :as sql]
            [clojure.spec.alpha :as s]))

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
(s/def ::id uuid?)
(s/def ::first_name string?)
(s/def ::last_name string?)
(s/def ::username (s/and string?))
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


(defn -create-user! [this user]
  {:pre [(s/valid? ::user user)]}
  (let [user-id (java.util.UUID/randomUUID)
        row (assoc user :id user-id)]
    (rdbms/insert! (:database this) "users" row)
    row))

(defn -create-login! [this id encrypted-password]
  (rdbms/insert! (:database this) "logins"
                 {:id id, :hashed_password encrypted-password}))

(defn -register-user-impl! [this
                            {:keys [role_id]
                             :as user
                             :or {role_id default-role}}
                            password]
  {:pre [(s/valid? ::user user)]}
  (let [{:keys [id] :as new-user}
        (create-user! this (assoc user :role_id role_id))]
    (create-login! this
                   id
                   (encryption/encrypt (encryption/make-encryption) password))
    new-user))

;;https://www.donedone.com/building-the-optimal-user-database-model-for-your-application/
(defn- -register-user! [this user password]
  (try
    (-register-user-impl! this user password)
    (catch Exception e
      (str "register-user! caught exception: " (.getMessage e)
           "this config: " (assoc (:conn (:database this)) :password "xxxxxx")))))


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
         (encryption/check (encryption/make-encryption)
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
