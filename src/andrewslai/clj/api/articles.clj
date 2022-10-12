(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.entities.article :as article])
  (:import java.time.LocalDateTime))

(def get-published-articles article/get-published-articles)
(def get-published-article article/get-published-article)
(def get-published-article-by-url article/get-published-article-by-url)

(def get-all-articles article/get-all-articles)

(def get-article article/get-article)

(defn create-article! [db article]
  (article/create-article! db (assoc article :timestamp (java.time.LocalDateTime/now))))
