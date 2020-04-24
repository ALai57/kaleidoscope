(ns andrewslai.clj.persistence.users-test
  (:require [andrewslai.clj.auth.crypto :as encryption]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.test-utils :refer [defdbtest]]
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

(def test-db
  (-> ptest/db-spec
      postgres/->Postgres
      users/->UserDatabase))

(def example-user {:avatar (byte-array (map (comp byte int) "Hello world!"))
                   :email "me@andrewslai.com"
                   :first_name "Andrew"
                   :last_name "Lai"
                   :username "Andrew"})

(defdbtest db-test ptest/db-spec
  (testing "register-user!"
    (users/register-user! test-db example-user "password")
    (is (= {:first_name "Andrew"
            :last_name "Lai"
            :username "Andrew"}
           (select-keys (-> test-db
                            (users/get-user "Andrew")
                            (dissoc :id))
                        [:first_name :last_name :username]))))
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
