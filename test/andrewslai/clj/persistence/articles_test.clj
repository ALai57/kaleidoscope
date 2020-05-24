(ns andrewslai.clj.persistence.articles-test
  (:require [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.test :refer [is testing]]
            [slingshot.test]
            [slingshot.slingshot :refer [try+]]))

(defn test-db []
  (-> ptest/db-spec
      postgres/->Postgres
      articles/->ArticleDatabase))

(def example-article {:title "My test article"
                      :article_tags "thoughts"
                      :article_url "my-test-article"                      
                      :author "Andrew Lai"})

(def example-content {:content "<h1>Hello world!</h1>"})

(defdbtest basic-db-test ptest/db-spec
  (let [[article-result content-result]
        (-> (test-db)
            (articles/create-full-article! (merge example-article
                                                  example-content)))]
    (testing "Create article!"
      (is (= example-article (dissoc article-result :timestamp :article_id))))
    (testing "Retrieve article"
      (is (= {:article-name "my-test-article"
              :article (merge article-result {:content content-result})}
             (articles/get-full-article (test-db) "my-test-article"))))))

(defdbtest exceptions-test ptest/db-spec
  (testing "Invalid article name"
    (is (thrown+? [:type :IllegalArgumentException]
                  (articles/get-full-article (test-db) 1)))))
