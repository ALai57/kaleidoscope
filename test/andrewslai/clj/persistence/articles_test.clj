(ns andrewslai.clj.persistence.articles-test
  (:require [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.embedded-h2 :refer [with-embedded-h2]]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(def example-article
  {:title        "My test article"
   :article_tags "thoughts"
   :article_url  "my-test-article"
   :author       "Andrew Lai"
   :content      "<h1>Hello world!</h1>"})

(deftest create-and-retrieve-articles-test
  (with-embedded-h2 datasource
    (let [database (pg/->NextDatabase datasource)]
      (testing "example-article doesn't exist in the database"
        (is (nil? (article/get-article database (:article_url example-article)))))

      (testing "Insert the example-article"
        (is (article/create-article! database example-article)))

      (testing "Can retrieve example-article from the DB"
        (is (match? example-article (article/get-article database (:article_url example-article))))))))
