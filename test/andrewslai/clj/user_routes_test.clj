(ns andrewslai.clj.user-routes-test
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.utils :refer [parse-response-body]]
            [cheshire.core :as json]
            [clojure.test :refer [deftest is testing]]
            [ring.mock.request :as mock]))

(extend-protocol users/UserPersistence
  clojure.lang.IAtom
  (get-user [a username]
    (first (filter #(= username (:username %))
                   (:users (deref a)))))
  (get-password [a user-id]
    (:hashed_password (first (filter #(= user-id (:id %))
                                     (:logins (deref a))))))
  (login [a credentials]
    (users/-login a credentials)))

(def test-user-db
  (atom {:users [{:id 1
                  :username "Andrew"}]
         :logins [{:id 1
                   :hashed_password (encryption/encrypt (encryption/make-encryption)
                                                        "Lai")}]}))

(def test-users-app (h/app {:user test-user-db}))

(deftest login-test
  (testing "login happy path"
    (let [credentials (json/generate-string {:username "Andrew"
                                             :password "Lai"})

          {:keys [status headers]} (test-users-app (mock/request :post
                                                                 "/login"
                                                                 credentials))]
      (is (= 302 status))
      (is (contains? headers "Set-Cookie"))))
  (testing "login with incorrect password"
    (let [credentials (json/generate-string {:username "Andrew"
                                             :password "Laia"})
          {:keys [status headers]} (test-users-app (mock/request :post
                                                                 "/login"
                                                                 credentials))]
      (is (= 302 status))
      (is (not (contains? headers "Set-Cookie"))))))
