(ns andrewslai.clj.entities.user
  (:require [andrewslai.clj.persistence :as p]
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
(s/def :andrewslai.user/user (s/keys :req-un [::avatar
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

(s/def :andrewslai.user/password
  (s/and string? sufficient-strength?))

(s/def :andrewslai.user/login
  (s/keys :req-un [:andrewslai.user/password
                   :andrewslai.user/id]))

;; TODO: Don't rely on Postgres in this code. Somehow push that further down so
;; you don't depend on implementation here. Insert can be generic enough to NOT
;; need any DB specifics inside it's method. Also postgres2 should be able to be
;; renamed RDBMS
(defn create-user-profile! [database user]
  (pg/insert! database
              :users user
              :ex-subtype :UnableToCreateUser
              ;; TODO: REmove this and use FDEF
              :input-validation :andrewslai.user/user))

(defn get-user-profiles
  ([database]
   (get-user-profiles nil))
  ([database where]
   (p/select database (merge {:select [:*]
                              :from [:users]}
                             where))))

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
