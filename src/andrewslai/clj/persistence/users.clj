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
            [slingshot.slingshot :refer [throw+ try+]]
            [andrewslai.clj.entities.user :as user])
  (:import (com.nulabinc.zxcvbn Zxcvbn)))


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

(s/def ::hashed_password string?)

(s/def ::login (s/keys :opt-un [::id
                                ::hashed_password]))

(defn create-login!
  [database id password]
  (let [login {:id id, :hashed_password (encrypt (make-encryption) password)}]
    (postgres2/insert! database
                       :logins login
                       :input-validation ::login
                       :ex-subtype :UnableToCreateLogin)))

(defn get-login [database user-id]
  (first (postgres2/select database {:select [:*]
                                     :from [:logins]
                                     :where [:= :users/id user-id]})))

(defn delete-login! [database id]
  (postgres2/delete! database :logins id))

(defn wrap-user [handler]
  (fn [{user-id :identity components :components :as req}]
    (if (and user-id (:database components))
      (handler (assoc req :user (user/get-user-profile-by-id (:database components) user-id)))
      (handler (assoc req :user nil)))))
