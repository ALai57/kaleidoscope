(ns andrewslai.clj.persistence.users-test
  (:require [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.test :refer [is testing]]
            [slingshot.test]
            [slingshot.slingshot :refer [try+]]))

(defn test-db []
  (-> ptest/db-spec
      postgres/->Postgres
      users/->UserDatabase))

(def example-user {:avatar "Hello world!"
                   :email "me@andrewslai.com"
                   :first_name "Andrew"
                   :last_name "Lai"
                   :username "Andrew"})

(def password "CactusGnarlObsidianTheft")

#_(defdbtest basic-db-test ptest/db-spec
    (testing "register-user!"
      (users/register-user! (test-db) example-user password)
      (is (= {:first_name "Andrew"
              :last_name "Lai"
              :username "Andrew"}
             (select-keys (-> (test-db)
                              (users/get-user "Andrew")
                              (dissoc :id))
                          [:first_name :last_name :username]))))
    (testing "get-user, get-password"
      (let [{:keys [id]} (users/get-user (test-db) "Andrew")]
        (is (uuid? id))
        (is (some? (users/get-password (test-db) id)))))
    (testing "verify-credentials"
      (is (not (users/verify-credentials (test-db) {:username "Andrew"
                                                    :password "Wrong"})))
      (is (users/verify-credentials (test-db) {:username "Andrew"
                                               :password password})))
    (testing "update-user"
      (is (= {:first_name "Werdna"}
             (users/update-user! (test-db) "Andrew" {:first_name "Werdna"})))
      (is (= (-> example-user
                 (assoc :first_name "Werdna")
                 (dissoc :avatar))
             (select-keys (users/get-user (test-db) "Andrew")
                          [:email :first_name :last_name :username]))))
    (testing "delete-user!"
      (is (= 1 (users/delete-user! (test-db) {:username "Andrew"
                                              :password password})))
      (is (nil? (users/get-user (test-db) "Andrew")))))

#_(defdbtest update-user-errors-test ptest/db-spec
    (users/register-user! (test-db) example-user password)
    (testing "Illegal last name"
      (is (thrown+? [:type :IllegalArgumentException]
                    (users/update-user! (test-db) (:username example-user) {:last_name ""}))))
    (testing "Illegal first name"
      (is (thrown+? [:type :IllegalArgumentException]
                    (users/update-user! (test-db) (:username example-user) {:first_name ""})))))

;; Does not belong here anymore
#_(defdbtest registration-errors-test ptest/db-spec
    (testing "Username includes escape chars"
      (is (thrown+? [:type :IllegalArgumentException]
                    (users/register-user! (test-db)
                                          (assoc example-user :username "Andrew;")
                                          password))))
    (testing "Weak password"
      (is (thrown+? [:type :IllegalArgumentException]
                    (users/register-user! (test-db) example-user "password"))))
    (testing "Invalid email"
      (is (thrown+? [:type :IllegalArgumentException]
                    (users/register-user! (test-db)
                                          (assoc example-user :email 1)
                                          password))))
    #_(doseq [field [:first_name :last_name :username :email]]
        (testing (str field " cannot be empty string")
          (is (thrown+? [:type :IllegalArgumentException]
                        (users/register-user! (test-db)
                                              (assoc example-user field "")
                                              password)))))
    #_(testing "Duplicate user"
        (is (thrown+? [:type :PSQLException]
                      (users/register-user! (test-db) example-user password)
                      (users/register-user! (test-db) example-user password)))))
