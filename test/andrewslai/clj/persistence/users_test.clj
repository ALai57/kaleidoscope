(ns andrewslai.clj.persistence.users-test
  (:require [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres2 :as postgres2]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.users :as users]
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
  (let [db (postgres/->Postgres ptest/db-spec)
        database (postgres2/->Database ptest/db-spec)]

    (testing "create-user! and get-user"
      (users/create-user! database example-user)
      (is (= (dissoc example-user :avatar)
             (dissoc (users/get-user database username) :avatar)))
      (is (= (dissoc example-user :avatar)
             (dissoc (users/get-user-by-id database id) :avatar))))

    (testing "create-login! and get-password"
      (users/create-login! database id password)
      (is (some? (users/-get-password db id))))

    (testing "update-user!"
      (let [first-name "Werdna"]
        (is (= "A" (:first_name (users/get-user database username))))
        (users/-update-user! db username {:first_name first-name})
        (is (= first-name (:first_name (users/get-user database username))))))

    (testing "delete-login!"
      (is (some? (users/-get-password db id)))
      (users/-delete-login! db id)
      (is (nil? (users/-get-password db id))))

    (testing "delete-user!"
      (is (some? (users/get-user database username)))
      (users/-delete-user! db id)
      (is (nil? (users/get-user database username))))))

(defdbtest update-user-errors-test ptest/db-spec
  (testing "Illegal last name"
    (is (thrown+? [:type :IllegalArgumentException]
                  (users/-update-user! nil username {:last_name ""}))))
  (testing "Illegal first name"
    (is (thrown+? [:type :IllegalArgumentException]
                  (users/-update-user! nil username {:first_name ""})))))

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
