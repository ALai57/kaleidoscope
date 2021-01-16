(ns andrewslai.clj.persistence.articles-test
  (:require [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.embedded-postgres :refer [with-embedded-postgres]]
            [clojure.test :refer [deftest is testing]]
            [slingshot.test]
            [slingshot.slingshot :refer [try+]]))

(def example-article {:title "My test article"
                      :article_tags "thoughts"
                      :article_url "my-test-article"                      
                      :author "Andrew Lai"
                      :content "<h1>Hello world!</h1>"})

(deftest basic-db-test
  (with-embedded-postgres database
    (let [article  (article/create-article! database example-article)]
      (testing "Create article!"
        (is (= example-article (dissoc article :timestamp :article_id))))
      (testing "Retrieve article"
        (is (= article (article/get-article database "my-test-article")))))))
