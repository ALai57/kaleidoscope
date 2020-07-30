(ns andrewslai.clj.persistence.users
  (:require [andrewslai.clj.auth.crypto :refer [encrypt check make-encryption]]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.utils :refer [file->bytes validate]]
            [clojure.data.codec.base64 :as b64]
            [clojure.java.data :as j]
            [clojure.java.io :as io]
            [clojure.java.jdbc :as sql]
            [clojure.spec.alpha :as s]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import (com.nulabinc.zxcvbn Zxcvbn)))

;; RESOURCES FOR AUTHENTICATION RELATED TOPICS
;; https://stackoverflow.com/questions/6832445/how-can-bcrypt-have-built-in-salts
;; https://funcool.github.io/buddy-auth/latest/#signed-jwt

;; TODO: Log client out after 30 mins

(defprotocol UserPersistence
  (create-user! [_ user])
  (create-login! [_ user-id password])
  (get-user [_ username])
  (get-user-by-id [_ user-id])
  (get-users [_])
  (update-user! [_ username update-payload])
  (get-password [_ user-id])
  (delete-user! [_ credentials])
  (verify-credentials [_ credentials])
  (login [_ credentials]))

(def default-avatar (-> "avatars/happy_emoji.jpg"
                        clojure.java.io/resource
                        file->bytes))
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
(s/def ::email (s/and string? email?))
(s/def ::avatar bytes?)
(s/def ::role_id (s/and int? pos?))
(s/def ::user (s/keys :req-un [::avatar
                               ::first_name
                               ::last_name
                               ::username
                               ::email
                               ::role_id
                               ::id]))

(s/def ::user-update (s/keys :opt-un [::first_name ::last_name ::avatar]))

(defn password-strength [password]
  (-> Zxcvbn
      new
      (.measure password)
      j/from-java
      (select-keys [:score :feedback])))

(defn sufficient-strength? [password]
  (let [{:keys [score]} (password-strength password)]
    (<= 4 score)))

(s/def ::password (s/and string? sufficient-strength?))

(comment
  (-> Zxcvbn
      new
      (.measure "password")
      j/from-java
      (select-keys [:score :feedback]))
  )

(defn- -create-user!
  "Checks that the user meets the ::user spec and creates a user"
  [this user]
  (validate ::user user :IllegalArgumentException)
  (rdbms/insert! (:database this) "users" user))

(defn- -create-login!
  "Checks that the login meets the ::password spec, then encrypts and
  persists the login information"
  [this id password]
  (validate ::password password :IllegalArgumentException)
  (let [encrypted-password (encrypt (make-encryption) password)]
    (rdbms/insert! (:database this) "logins"
                   {:id id, :hashed_password encrypted-password})))

;;https://www.donedone.com/building-the-optimal-user-database-model-for-your-application/
(defn -get-users [{:keys [database]}]
  (rdbms/hselect database {:select [:*]
                           :from [:users]}))

(defn -get-user [{:keys [database]} username]
  (first (rdbms/hselect database {:select [:*]
                                  :from [:users]
                                  :where [:= :users/username username]})))

(defn -get-user-by-id [{:keys [database]} user-id]
  (first (rdbms/hselect database {:select [:*]
                                  :from [:users]
                                  :where [:= :users/id user-id]})))

(defn -update-user! [{:keys [database]} username update-payload]
  ;; TODO: Move this validation to the public API fns
  (validate ::user-update update-payload :IllegalArgumentException)
  (let [n-updates (first (rdbms/update! database
                                        "users"
                                        update-payload
                                        {:username username}))]
    (if (= 1 n-updates)
      update-payload)))

(defn -get-password [{:keys [database]} user-id]
  (:hashed_password (first (rdbms/hselect database
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

(defn -delete-user! [{:keys [database] :as this} id]
  (let [n-logins (first (rdbms/delete! database "logins" {:id id}))
        n-users (first (rdbms/delete! database "users" {:id id}))]
    n-users))

(defrecord UserDatabase [database]
  UserPersistence
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
  (get-password [this user-id]
    (-get-password this user-id))
  (update-user! [this username update-payload]
    (-update-user! this username update-payload))
  (delete-user! [this credentials]
    (-delete-user! this credentials))
  (verify-credentials [this credentials]
    (-verify-credentials this credentials)))

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
