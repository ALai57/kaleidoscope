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

          response (test-users-app (mock/request :post
                                                 "/login"
                                                 credentials))]
      (is (= 200 (:status response)))
      (is (= true (:logged-in? (parse-response-body response))))))
  (testing "login incorrect"
    (let [credentials (json/generate-string {:username "Andrew"
                                             :password "Laia"})
          response (test-users-app (mock/request :post
                                                 "/login"
                                                 credentials))]
      (is (= 200 (:status response)))
      (is (= false (:logged-in? (parse-response-body response)))))))
