(ns kaleidoscope.api.restaurants-test
  (:require [kaleidoscope.api.restaurants :as restaurants]
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

(deftest create-and-retrieve-eater-group-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-group doesn't exist in the database"
      (is (empty? (#'restaurants/get-eater-groups database example-group))))

    (let [[{group-id :id} :as result] (restaurants/create-eater-group! database example-group)]
      (testing "Insert the example-group"
        (is (not-empty result)))

      (testing "Can retrieve example-group from the DB"
        (is (match? [example-group]
                    (#'restaurants/get-eater-groups database example-group))))

      (testing "Ownership predicate"
        (is (restaurants/owns? database "user-1" group-id)))

      (testing "Non-owner cannot delete the group"
        (is (nil? (restaurants/delete-eater-group! database "not-the-owner" group-id))))

      (testing "Group owner can delete the group"
        (is (= [] (restaurants/delete-eater-group! database "user-1" group-id)))
        (is (empty? (#'restaurants/get-eater-groups database example-group)))))))

(deftest create-and-retrieve-eater-group-memberships-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "no group-memberships exist in the database"
      (is (empty? (restaurants/get-users-eater-groups database "user-1"))))

    (let [[{group-id :id}]        (restaurants/create-eater-group! database example-group)
          [{membership-id-1 :id}] (restaurants/add-users-to-eater-group! database "user-1" group-id {:email "b@z.com"
                                                                                                     :alias "foo"})
          [{membership-id-2 :id}] (restaurants/add-users-to-eater-group! database "user-1" group-id {:email "c@z.com"
                                                                                                     :alias "bar"})]
      (testing "Add two users to the group"
        (is (and group-id
                 membership-id-1
                 membership-id-2)))

      (testing "Adding user fails if not requested by the group owner"
        (is (nil? (restaurants/add-users-to-eater-group! database "not-the-owner" group-id "user-4"))))

      (testing "Can retrieve users in the group"
        (is (match? [{:eater-group-id group-id
                      :owner-id       "user-1"
                      :display-name   "mygroup"
                      :memberships    [{:membership-id string? :membership-created-at inst? :email "b@z.com" :alias "foo"}
                                       {:membership-id string? :membership-created-at inst? :email "c@z.com" :alias "bar"}]}]
                    (restaurants/get-users-eater-groups database "user-1"))))

      (testing "Delete a user fails if not group owner"
        (is (nil? (restaurants/remove-user-from-eater-group! database "not-the-owner" group-id membership-id-1))))

      (testing "Delete a user in the group"
        (is (empty? (restaurants/remove-user-from-eater-group! database "user-1" group-id membership-id-1)))
        (is (= 1 (count (restaurants/get-users-eater-groups database "user-1")))))
      )))
