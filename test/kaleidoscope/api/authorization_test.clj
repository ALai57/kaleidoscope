(ns kaleidoscope.api.authorization-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.authorization :as auth]))

(defn- writer-request
  [identity]
  {:identity    identity
   :server-name "andrewslai.com"
   :uri         "/projects"})

(deftest require-*-writer-test
  (testing "A verified user with the *-writer role is granted access"
    (is (true? (auth/require-*-writer
                (writer-request {:type :verified-user :roles #{"andrewslai.com:writer"}})))))

  (testing "A service-account with the *-writer role is granted access"
    (is (true? (auth/require-*-writer
                (writer-request {:type :service-account :roles #{"andrewslai.com:writer"}})))))

  (testing "A service-account with the *-admin role is granted access"
    (is (true? (auth/require-*-writer
                (writer-request {:type :service-account :roles #{"andrewslai.com:admin"}})))))

  (testing "A service-account without a matching role is rejected"
    (is (not (true? (auth/require-*-writer
                      (writer-request {:type :service-account :roles #{"other-host.com:writer"}}))))))

  (testing "A verified user without the role is rejected"
    (is (not (true? (auth/require-*-writer
                      (writer-request {:type :verified-user :roles #{}}))))))

  (testing "An unverified user is rejected regardless of roles"
    (is (not (true? (auth/require-*-writer
                      (writer-request {:type :unverified-user :roles #{"andrewslai.com:writer"}})))))))

(deftest require-*-reader-test
  (testing "A verified user with the *-reader role is granted access"
    (is (true? (auth/require-*-reader
                (writer-request {:type :verified-user :roles #{"andrewslai.com:reader"}})))))

  (testing "A service-account with the *-reader role is granted access"
    (is (true? (auth/require-*-reader
                (writer-request {:type :service-account :roles #{"andrewslai.com:reader"}})))))

  (testing "A verified user with the *-admin role is granted access"
    (is (true? (auth/require-*-reader
                (writer-request {:type :verified-user :roles #{"andrewslai.com:admin"}})))))

  (testing "A verified user without the role is rejected"
    (is (not (true? (auth/require-*-reader
                      (writer-request {:type :verified-user :roles #{}}))))))

  (testing "An unverified user is rejected regardless of roles"
    (is (not (true? (auth/require-*-reader
                      (writer-request {:type :unverified-user :roles #{"andrewslai.com:reader"}})))))))

(deftest require-*-admin-test
  (testing "A verified user with the *-admin role is granted access"
    (is (true? (auth/require-*-admin
                (writer-request {:type :verified-user :roles #{"andrewslai.com:admin"}})))))

  (testing "A service-account with the *-admin role is granted access"
    (is (true? (auth/require-*-admin
                (writer-request {:type :service-account :roles #{"andrewslai.com:admin"}})))))

  (testing "A verified user without the role is rejected"
    (is (not (true? (auth/require-*-admin
                      (writer-request {:type :verified-user :roles #{}}))))))

  (testing "An unverified user is rejected regardless of roles"
    (is (not (true? (auth/require-*-admin
                      (writer-request {:type :unverified-user :roles #{"andrewslai.com:admin"}})))))))

(deftest require-role-test
  (testing "A verified user with the role is granted access"
    (is (true? (auth/require-role "myrole"
                                  (writer-request {:type :verified-user :roles #{"myrole"}})))))

  (testing "A service-account with the role is granted access"
    (is (true? (auth/require-role "myrole"
                                  (writer-request {:type :service-account :roles #{"myrole"}})))))

  (testing "A verified user without the role is rejected"
    (is (not (true? (auth/require-role "myrole"
                                       (writer-request {:type :verified-user :roles #{}}))))))

  (testing "An unverified user is rejected regardless of roles"
    (is (not (true? (auth/require-role "myrole"
                                       (writer-request {:type :unverified-user :roles #{"myrole"}})))))))
