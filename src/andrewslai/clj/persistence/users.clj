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

(defn delete-login! [database id]
  (postgres2/delete! database :logins id))

(defn delete-user! [database id]
  (postgres2/delete! database :users id))

(defn wrap-user [handler]
  (fn [{user-id :identity components :components :as req}]
    (if (and user-id (:database components))
      (handler (assoc req :user (get-user-by-id (:database components) user-id)))
      (handler (assoc req :user nil)))))
