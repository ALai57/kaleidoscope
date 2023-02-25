(ns andrewslai.clj.api.groups-test
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.api.groups :as groups]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.test-main :as tm]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(def example-group
  {:display-name "mygroup"
   :owner-id     "user-1"})

(deftest create-and-retrieve-group-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-group doesn't exist in the database"
      (is (empty? (groups/get-groups database example-group))))

    (let [[{group-id :id} :as result] (groups/create-group! database example-group)]
      (testing "Insert the example-group"
        (is (not-empty result)))

      (testing "Can retrieve example-group from the DB"
        (is (match? [example-group]
                    (groups/get-groups database example-group))))

      (testing "Ownership predicate"
        (is (groups/owns? database "user-1" group-id)))

      (testing "Non-owner cannot delete the group"
        (is (nil? (groups/delete-group! database "not-the-owner" group-id))))

      (testing "Group owner can delete the group"
        (is (= [] (groups/delete-group! database "user-1" group-id)))
        (is (empty? (groups/get-groups database example-group)))))))

(deftest create-and-retrieve-group-memberships-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "no group-memberships exist in the database"
      (is (empty? (groups/get-group-memberships database {}))))

    (let [[{group-id :id}] (groups/create-group! database example-group)
          [{membership-id-1 :id}] (groups/add-users-to-group! database group-id "user-2")
          [{membership-id-2 :id}] (groups/add-users-to-group! database group-id "user-3")]
      (testing "Add two users to the group"
        (is (and group-id
                 membership-id-1
                 membership-id-2)))

      (testing "Can retrieve users in the group"
        (is (= 2 (count (groups/get-group-memberships database)))))

      (testing "Delete a user in the group"
        (is (empty? (groups/remove-user-from-group! database membership-id-1)))
        (is (= 1 (count (groups/get-group-memberships database)))))
      )))
