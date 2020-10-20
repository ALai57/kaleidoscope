(ns andrewslai.clj.entities.user
  (:require [andrewslai.clj.auth.crypto :refer [encrypt check make-encryption]]
            [andrewslai.clj.persistence :as p]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [clojure.java.data :as j]
            [clojure.spec.alpha :as s])
  (:import com.nulabinc.zxcvbn.Zxcvbn))

(def email-regex #"^[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\.[a-zA-Z]{2,63}$")
(defn email? [s] (when (string? s)
                   (re-matches email-regex s)))

(defn alphanumeric? [s]
  (some? (re-matches #"^[a-zA-Z0-9-_]+$" s)))

(s/def :andrewslai.user/id uuid?)
(s/def :andrewslai.user/first_name (s/and string? #(< 0 (count %))))
(s/def :andrewslai.user/last_name (s/and string? #(< 0 (count %))))
(s/def :andrewslai.user/username (s/and string? #(< 0 (count %)) alphanumeric?))
(s/def :andrewslai.user/email (s/and string? email?))
(s/def :andrewslai.user/avatar bytes?)
(s/def :andrewslai.user/role_id (s/and int? pos?))
(s/def :andrewslai.user/user-profile (s/keys :req-un [::avatar
                                                      ::first_name
                                                      ::last_name
                                                      ::username
                                                      ::email
                                                      ::role_id
                                                      ::id]))

(s/def :andrewslai.user/user-update (s/keys :opt-un [:andrewslai.user/first_name
                                                     :andrewslai.user/last_name
                                                     :andrewslai.user/avatar]))

(defn password-strength [password]
  (-> Zxcvbn
      new
      (.measure password)
      j/from-java
      (select-keys [:score :feedback])))

(defn sufficient-strength? [password]
  (let [{:keys [score]} (password-strength password)]
    (<= 4 score)))

(s/def :andrewslai.user/hashed_password
  (s/and string? sufficient-strength?))

(s/def :andrewslai.user/login
  (s/keys :req-un [:andrewslai.user/hashed_password
                   :andrewslai.user/id]))

(defn create-user-profile! [database user]
  (pg/insert! database
              :users user
              :ex-subtype :UnableToCreateUser
              :input-validation :andrewslai.user/user-profile))

;; TODO: These should not be different queries...
(defn get-user-profile [database username]
  (first (pg/select database {:select [:*]
                              :from [:users]
                              :where [:= :users/username username]})))

(defn get-user-profile-by-id [database user-id]
  (first (pg/select database {:select [:*]
                              :from [:users]
                              :where [:= :users/id user-id]})))

(defn update-user-profile! [database username update-payload]
  (pg/update! database :users
              update-payload username
              :input-validation :andrewslai.user/user-update
              :ex-subtype :UnableToUpdateUser))

(defn delete-user-profile! [database id]
  (pg/delete! database :users id))

(defn create-user-login!
  [database id password]
  (let [login {:id id, :hashed_password (encrypt (make-encryption) password)}]
    (pg/insert! database
                :logins login
                :input-validation :andrewslai.user/login
                :ex-subtype :UnableToCreateLogin)))

(defn get-user-login [database user-id]
  (first (pg/select database {:select [:*]
                              :from [:logins]
                              :where [:= :users/id user-id]})))

(defn delete-user-login! [database id]
  (pg/delete! database :logins id))


;; TODO: REFACTOR S/T ALL USER NS ARE IN A USER FOLDER
;;











(comment
  (require '[andrewslai.clj.dev-tools :as tools])

  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"
     :role_id    2})

  (def user-db
    (->UserDB (tools/postgres-db)))

  (u/create-user! user-db
                  example-user)

  (u/get-users user-db)

  )
