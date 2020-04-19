(ns andrewslai.clj.persistence.users-test
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-response-body
                                          body->map
                                          file->bytes]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication
                                           wrap-authorization]]
            [cheshire.core :as json]
            [clojure.data.codec.base64 :as b64]
            [clojure.test :refer [deftest is testing]]
            [compojure.api.sweet :refer [api GET POST]]
            [ring.middleware.cookies :refer [wrap-cookies]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]))

;; https://github.com/whostolebenfrog/lein-postgres
;; https://eli.naeher.name/embedded-postgres-in-clojure/
(defn find-user-index [username v]
  (keep-indexed (fn [idx user] (when (= username (:username user)) idx))
                v))

(extend-protocol users/UserPersistence
  clojure.lang.IAtom
  (create-user! [a user]
    (swap! a update :users conj (users/create-user-payload user))
    user)
  (create-login! [a id password]
    (swap! a update :logins conj {:id id, :hashed_password password}))
  (register-user! [a user]
    (users/-register-user-impl! a user))
  (update-user [a username update-map]
    (let [idx (first (find-user-index username (:users @a)))]
      (swap! a update-in [:users idx] merge update-map)
      (get-in @a [:users idx])))
  (get-user-by-id [a user-id]
    (first (filter #(= user-id (:id %))
                   (:users (deref a)))))
  (get-user [a username]
    (first (filter #(= username (:username %))
                   (:users (deref a)))))
  (get-password [a user-id]
    (:hashed_password (first (filter #(= user-id (:id %))
                                     (:logins (deref a))))))
  (verify-credentials [a credentials]
    (users/-verify-credentials a credentials))
  (delete-user! [a {:keys [username password] :as credentials}]
    (when (users/verify-credentials a credentials)
      (let [{:keys [id]} (users/get-user a username)
            updated-users (remove #(= username (:username %)) (:users @a))
            updated-login (remove #(= id (:id %)) (:logins @a))]
        (swap! a assoc :users updated-users)
        (if (users/get-user a username) 0 1))))
  (login [a credentials]
    (users/-login a credentials)))

(def test-db
  (atom {:users []
         :logins []}))

(def example-user {:avatar (byte-array (map (comp byte int) "Hello world!"))
                   :email "me@andrewslai.com"
                   :first_name "Andrew"
                   :last_name "Lai"
                   :password "password"
                   :username "Andrew"})

(deftest db-test
  (testing "register-user!"
    (users/register-user! test-db example-user)
    (is (= (dissoc (users/create-user-payload example-user) :id)
           (dissoc (users/get-user test-db "Andrew") :id))))
  (testing "get-user, get-password"
    (let [{:keys [id]} (users/get-user test-db "Andrew")]
      (is (uuid? id))
      (is (some? (users/get-password test-db id)))))
  (testing "verify-credentials"
    (is (not (users/verify-credentials test-db {:username "Andrew"
                                                :password "Wrong"})))
    (is (users/verify-credentials test-db {:username "Andrew"
                                           :password "password"})))
  (testing "delete-user!"
    (is (= 1 (users/delete-user! test-db {:username "Andrew"
                                          :password "password"})))
    (is (nil? (users/get-user test-db "Andrew")))))
