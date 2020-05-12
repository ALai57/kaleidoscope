(ns andrewslai.cljs.events.articles-test
  (:require [andrewslai.cljs.events.articles :as a]
            [cljs.test :refer-macros [deftest is testing]]))

(deftest articles-db-events 
  (testing "Load article"
    (is (= {:loading? false, :active-content {:article-name "Something"}}
           (a/load-article {} [nil {:article-name "Something"}]))))
  (testing "Load recent articles"
    (is (= {:loading? false, :recent-content [{:article-name "Something"}]}
           (a/load-recent-articles {} [nil [{:article-name "Something"}]])))))

