(ns andrewslai.clj.persistence.postgres2-test
  (:require [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.postgres2 :as postgres]
            [andrewslai.clj.persistence :as p]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.test :refer [is testing]]
            [honeysql.helpers :as hh]))

(def example-article {:title "My test article"
                      :article_tags "thoughts"
                      :article_url "my-test-article"
                      :author "Andrew Lai"
                      :content "<h1>Hello world!</h1>"})

(def example-user
  {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
   :first_name "Andrew"
   :last_name  "Lai"
   :username   "alai"
   :avatar     nil
   :email      "andrew@andrew.com"
   :role_id    2})

(defdbtest insert-test ptest/db-spec
  (let [db        (postgres/->Database ptest/db-spec)
        all-users {:select [:*] :from [:users]}
        add-user  (-> (hh/insert-into :users)
                      (hh/values [example-user]))
        del-user  (-> (hh/delete-from :users)
                      (hh/where [:= :users/username (:username example-user)]))]

    (is (empty? (p/select db all-users)))
    (is (= example-user (p/transact! db add-user)))
    (let [result (p/select db all-users)]
      (is (= 1 (count result)))
      (is (= example-user (first result))))
    (is (= example-user (p/transact! db del-user)))
    (is (empty? (p/select db all-users)))))
