(ns andrewslai.clj.persistence.articles-test
  (:require [andrewslai.clj.persistence.postgres2 :as postgres2]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.test :refer [is testing]]
            [slingshot.test]
            [slingshot.slingshot :refer [try+]]))

(def example-article {:title "My test article"
                      :article_tags "thoughts"
                      :article_url "my-test-article"                      
                      :author "Andrew Lai"
                      :content "<h1>Hello world!</h1>"})

(defdbtest basic-db-test ptest/db-spec
  (let [database (postgres2/->Database ptest/db-spec)
        article  (article/create-article! database example-article)]
    (testing "Create article!"
      (is (= example-article (dissoc article :timestamp :article_id))))
    (testing "Retrieve article"
      (is (= article (article/get-article database "my-test-article"))))))

#_(defdbtest exceptions-test ptest/db-spec
    (testing "Invalid article name"
      (is (thrown+? [:type :IllegalArgumentException]
                    (article/get-article (test-db) 1)))))
