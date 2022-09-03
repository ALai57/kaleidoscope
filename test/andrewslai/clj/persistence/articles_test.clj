(ns andrewslai.clj.persistence.articles-test
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.persistence.embedded-h2 :as embedded-h2]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(def example-article
  {:title        "My test article"
   :article-tags "thoughts"
   :article-url  "my-test-article"
   :author       "Andrew Lai"
   :content      "<h1>Hello world!</h1>"})

(deftest create-and-retrieve-articles-test
  (let [database (embedded-h2/fresh-db!)]
    (testing "example-article doesn't exist in the database"
      (is (nil? (article/get-article database (:article-url example-article)))))

    (testing "Insert the example-article"
      (is (article/create-article! database example-article)))

    (testing "Can retrieve example-article from the DB"
      (is (match? example-article (article/get-article database (:article-url example-article)))))))
