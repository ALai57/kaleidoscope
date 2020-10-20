(ns andrewslai.clj.persistence.users-test
  (:require [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres2 :as postgres2]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.entities.user :as user]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.test :refer [is testing]]
            [slingshot.test]
            [slingshot.slingshot :refer [try+]]))



(def avatar (->> "Hello world!"
                 (map (comp byte int))
                 byte-array))
(def id  #uuid "3fa8af93-0e13-471b-a96f-a15d420e1463")
(def username "Andrew")
(def example-user {:avatar avatar
                   :email "me@andrewslai.com"
                   :first_name "A"
                   :id id
                   :last_name "Lai"
                   :role_id 2
                   :username username})

(def password "CactusGnarlObsidianTheft")

(defdbtest basic-db-test ptest/db-spec
  (let [database (postgres2/->Database ptest/db-spec)]

    (testing "create-user-profile! and get-user"
      (user/create-user-profile! database example-user)
      (is (= (dissoc example-user :avatar)
             (dissoc (user/get-user-profile database username) :avatar)))
      (is (= (dissoc example-user :avatar)
             (dissoc (user/get-user-profile-by-id database id) :avatar))))

    (testing "create-user-login! and get-user-login"
      (user/create-user-login! database id password)
      (is (some? (user/get-user-login database id))))

    (testing "update-user-profile!"
      (let [first-name "Werdna"]
        (is (= "A" (:first_name (user/get-user-profile database username))))
        (user/update-user-profile! database username {:first_name first-name})
        (is (= first-name (:first_name (user/get-user-profile database username))))))

    (testing "delete-user-login!"
      (is (some? (user/get-user-login database id)))
      (user/delete-user-login! database id)
      (is (nil? (user/get-user-login database id))))

    (testing "delete-user-profile!"
      (is (some? (user/get-user-profile database username)))
      (user/delete-user-profile! database id)
      (is (nil? (user/get-user-profile database username))))))

(defdbtest update-user-profile-errors-test ptest/db-spec
  (testing "Illegal last name"
    (is (thrown+? [:type :IllegalArgumentException]
                  (user/update-user-profile! nil username {:last_name ""}))))
  (testing "Illegal first name"
    (is (thrown+? [:type :IllegalArgumentException]
                  (user/update-user-profile! nil username {:first_name ""})))))

#_(defn test-db []
    (-> ptest/db-spec
        postgres/->Postgres
        users/->UserDatabase))

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
