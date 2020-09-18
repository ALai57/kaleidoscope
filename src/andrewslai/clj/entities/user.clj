(ns andrewslai.clj.entities.user
  (:require [clojure.java.data :as j]
            [clojure.spec.alpha :as s])
  (:import (com.nulabinc.zxcvbn Zxcvbn)))

(defprotocol User
  (create-login! [_ user-id password])
  (delete-login! [_ credentials])
  (create-user! [_ user])
  (delete-user! [_ credentials])
  (get-users [_])
  (get-password [_ user-id])
  (update-user! [_ username update-payload]))

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
