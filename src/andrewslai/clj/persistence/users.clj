(ns andrewslai.clj.persistence.users
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.persistence.postgres :as postgres]
            [clojure.java.jdbc :as sql]))

;; RESOURCES FOR AUTHENTICATION RELATED TOPICS
;; https://stackoverflow.com/questions/6832445/how-can-bcrypt-have-built-in-salts
;; https://funcool.github.io/buddy-auth/latest/#signed-jwt

;; TODO: Log client out after 30 mins

;; TODO: Basic endpoint for adding a new username
;; TODO: Verify that username doesn't already exist
;; TODO: If username exists, throw a non-200


(defprotocol UserPersistence
  (create-user! [_ user])
  (get-user [_ username])
  (get-user-by-id [_ id])
  (get-users [_])
  (get-password [_ user-id])
  (login [_ credentials]))

;;https://www.donedone.com/building-the-optimal-user-database-model-for-your-application/
(defn- -create-user! [db {:keys [username email first_name last_name password] :as user}]
  (try
    (let [id (java.util.UUID/randomUUID)
          result (sql/with-db-transaction [conn (:conn db)]
                   (sql/insert! (:conn db) "users" {:id id
                                                    :first_name first_name
                                                    :last_name last_name
                                                    :username username
                                                    :email email
                                                    :role_id 2})
                   (sql/insert! (:conn db) "logins"
                                {:id id
                                 :hashed_password
                                 (encryption/encrypt (encryption/make-encryption)
                                                     password)}))]
      #_(println "Insert successful!" result))
    (catch Exception e
      (str "create-user! caught exception: " (.getMessage e)
           "postgres config: " (assoc (:conn db) :password "xxxxxx")))))

(defn -get-users [db]
  (sql/query (:conn db) ["SELECT * FROM users"]))

(defn -get-user [db username]
  (first (sql/query (:conn db) ["SELECT * FROM users WHERE username = ?" username])))

(defn -get-user-by-id [db user-id]
  (first (sql/query (:conn db) ["SELECT * FROM users WHERE id = ?" user-id])))

(defn- -get-password [db user-id]
  (:hashed_password (first (sql/query (:conn db) ["SELECT hashed_password FROM logins WHERE id = ?" user-id]))))

(defn -login [db {:keys [username password]}]
  (let [{:keys [id]} (get-user db username)]
    (when (and id
               (get-password db id)
               (encryption/check (encryption/make-encryption)
                                 password
                                 (get-password db id)))
      id)))

(defrecord UserDatabase [conn]
  UserPersistence
  (create-user! [this user]
    (-create-user! this user))
  (get-users [this]
    (-get-users this))
  (get-user [this username]
    (-get-user this username))
  (get-user-by-id [this user-id]
    (-get-user-by-id this user-id))
  (get-password [this user-id]
    (-get-password this user-id))
  (login [this credentials]
    (-login this credentials)))

(defn wrap-user [handler]
  (fn [{user-id :identity components :components :as req}]
    (if (and user-id (:user components))
      (handler (assoc req :user (get-user-by-id (:user components) user-id)))
      (handler (assoc req :user nil)))))

(comment
  (create-user! (->UserDatabase postgres/pg-db)
                {:username "testuser"
                 :email "testuser@andrewlai.com"
                 :first_name "test"
                 :last_name "user"
                 :password "password"})
  (get-users (->UserDatabase postgres/pg-db))
  (get-user (->UserDatabase postgres/pg-db) "testuser")

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
               ["last_name = ?" "user"])



  (create-user! {:username "andrewlai"
                 :email "andrewlai@andrewlai.com"
                 :first_name "andrew"
                 :last_name "lai"
                 :password "mypassword"})
  )
