(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.entities.article :as article])
  (:import java.time.LocalDateTime))

(def get-all-articles article/get-all-articles)

(def get-article article/get-article)

(defn create-article! [db article]
  (article/create-article! db (assoc article :timestamp (java.time.LocalDateTime/now))))
