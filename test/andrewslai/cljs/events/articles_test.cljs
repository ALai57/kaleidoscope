(ns andrewslai.cljs.events.articles-test
  (:require [andrewslai.cljs.events.articles :as a]
            [cljs.test :refer-macros [deftest is testing]]
            [matcher-combinators.standalone :as sa]))

(def example-article
  {:title "article"
   :article_tags "tag"
   :author "Andrew Lai"
   :timestamp "2021-04-05"
   :article_url "http://google.com"
   :article_id 1
   :content "content"})

(deftest articles-db-events 
  (testing "Load article"
    (is (sa/match? {:active-content {:article-name "Something"}}
                   (a/load-article {} [nil {:article-name "Something"}]))))
  (testing "Load recent articles"
    (is (sa/match? {:recent-content [example-article]}
                   (a/load-recent-articles {} [nil [example-article
                                                    {:article-name "Something"}]])))))
