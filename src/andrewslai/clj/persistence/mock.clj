(ns andrewslai.clj.persistence.mock
  (:require [andrewslai.clj.persistence.core :refer [Persistence] :as core]))

(defn- save-article! []
  (println "Saved article!"))

(defn- get-all-articles []
  [{:title "Test article",
    :article_tags "thoughts"
    :timestamp #inst "2019-11-07T00:48:08.136082000-00:00"
    :author "Andrew Lai"
    :article_url "test-article"
    :article_id 10}
   {:title "Databases without Databases"
    :article_tags "thoughts"
    :timestamp #inst "2019-11-05T03:10:39.191325000-00:00"
    :author "Andrew Lai"
    :article_url "databases-without-databases"
    :article_id 8}
   {:title "Neural network explanation"
    :article_tags "thoughts"
    :timestamp #inst "2019-11-02T02:05:30.298225000-00:00"
    :author "Andrew Lai"
    :article_url "neural-network-explanation"
    :article_id 6}])

(defn- get-full-article [article-name]
  {:title "Test article"
   :article_tags "thoughts"
   :timestamp #inst "2019-11-07T00:48:08.136082000-00:00"
   :author "Andrew Lai"
   :article_url article-name
   :article_id 10
   :content
   '({:article_id 10
      :content (slurp (clojure.java.io/resource "example-article-content.html"))
      :dynamicjs []})})

(defn- get-article-metadata []
  (println "Saved article!"))

(defn make-db []
  (reify Persistence
    (save-article! [_]
      nil)
    (get-all-articles [_]
      (get-all-articles))
    (get-full-article [_ article-name]
      (get-full-article article-name))))

(comment
  (core/get-all-articles (make-db)))
