(ns kaleidoscope.api.groups-test
  (:require [kaleidoscope.api.groups :as groups]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    ;; Manually override the minimum error level because some of the tests will
    ;; emit warnings
    (log/with-min-level :error
      (f))))

(def example-group
  {:display-name "mygroup"
   :owner-id     "user-1"})

(deftest create-and-retrieve-group-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-group doesn't exist in the database"
      (is (empty? (#'groups/get-groups database example-group))))

    (let [[{group-id :id} :as result] (groups/create-group! database example-group)]
      (testing "Insert the example-group"
        (is (not-empty result)))

      (testing "Can retrieve example-group from the DB"
        (is (match? [example-group]
                    (#'groups/get-groups database example-group))))

      (testing "Ownership predicate"
        (is (groups/owns? database "user-1" group-id)))

      (testing "Non-owner cannot delete the group"
        (is (nil? (groups/delete-group! database "not-the-owner" group-id))))

      (testing "Group owner can delete the group"
        (is (= [] (groups/delete-group! database "user-1" group-id)))
        (is (empty? (#'groups/get-groups database example-group)))))))

(deftest create-and-retrieve-group-memberships-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "no group-memberships exist in the database"
      (is (empty? (groups/get-users-groups database "user-1"))))

    (let [[{group-id :id}]        (groups/create-group! database example-group)
          [{membership-id-1 :id}] (groups/add-users-to-group! database "user-1" group-id {:email "b@z.com"
                                                                                          :alias "foo"})
          [{membership-id-2 :id}] (groups/add-users-to-group! database "user-1" group-id {:email "c@z.com"
                                                                                          :alias "bar"})]
      (testing "Add two users to the group"
        (is (and group-id
                 membership-id-1
                 membership-id-2)))

      (testing "Adding user fails if not requested by the group owner"
        (is (nil? (groups/add-users-to-group! database "not-the-owner" group-id "user-4"))))

      (testing "Can retrieve users in the group"
        (is (match? [{:group-id     group-id
                      :owner-id     "user-1"
                      :display-name "mygroup"
                      :memberships  [{:membership-id string? :membership-created-at inst? :email "b@z.com" :alias "foo"}
                                     {:membership-id string? :membership-created-at inst? :email "c@z.com" :alias "bar"}]}]
                    (groups/get-users-groups database "user-1"))))

      (testing "Delete a user fails if not group owner"
        (is (nil? (groups/remove-user-from-group! database "not-the-owner" group-id membership-id-1))))

      (testing "Delete a user in the group"
        (is (empty? (groups/remove-user-from-group! database "user-1" group-id membership-id-1)))
        (is (= 1 (count (groups/get-users-groups database "user-1")))))
      )))
