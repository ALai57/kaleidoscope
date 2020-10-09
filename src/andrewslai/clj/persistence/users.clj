(ns andrewslai.clj.persistence.users
  (:require [andrewslai.clj.auth.crypto :refer [encrypt check make-encryption]]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres2 :as postgres2]
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
  (delete-login! [_ credentials])
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

(s/def ::hashed_password string?)

(s/def ::login (s/keys :opt-un [::id
                                ::hashed_password]))

(comment
  (-> Zxcvbn
      new
      (.measure "password")
      j/from-java
      (select-keys [:score :feedback]))
  )

(defn create-user!
  "Checks that the user meets the ::user spec and creates a user"
  [database user]
  (postgres2/insert! database
                     :users user
                     :input-validation ::user
                     :ex-subtype :UnableToCreateUser))

(defn create-login!
  "Checks that the login meets the ::password spec, then encrypts and
  persists the login information"
  [database id password]
  (validate ::password password :IllegalArgumentException)
  (let [login {:id id, :hashed_password (encrypt (make-encryption) password)}]
    (postgres2/insert! database
                       :logins login
                       :input-validation ::login
                       :ex-subtype :UnableToCreateLogin)))

(defn get-user [database username]
  (first (postgres2/select database {:select [:*]
                                     :from [:users]
                                     :where [:= :users/username username]})))

(defn get-user-by-id [database user-id]
  (first (postgres2/select database {:select [:*]
                                     :from [:users]
                                     :where [:= :users/id user-id]})))

(defn get-password [database user-id]
  (-> database
      (postgres2/select {:select [:*]
                         :from [:logins]
                         :where [:= :users/id user-id]})
      first
      :hashed_password))

(defn update-user! [database username update-payload]
  (postgres2/update! database :users
                     update-payload username
                     :input-validation ::user-update
                     :ex-subtype :UnableToUpdateUser))

(defn -delete-login! [database id]
  (first (rdbms/delete! database "logins" {:id id})))

(defn -delete-user! [database id]
  (first (rdbms/delete! database "users" {:id id})))

(defrecord UserDatabase [database]
  UserPersistence
  (delete-user! [this id]
    (-delete-user! database id))
  (delete-login! [this id]
    (-delete-login! database id)))

(defn wrap-user [handler]
  (fn [{user-id :identity components :components :as req}]
    (if (and user-id (:database components))
      (handler (assoc req :user (get-user-by-id (:database components) user-id)))
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
