(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.entities.article :as article])
  (:import java.time.LocalDateTime))

(defn get-all-articles [db]
  (article/get-all-articles db))

(defn get-article [db article-name]
  (article/get-article db article-name))

(defn create-article! [db article]
  (article/create-article! db (assoc article :timestamp (java.time.LocalDateTime/now))))
