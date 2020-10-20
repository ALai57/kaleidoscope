(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.entities.article :as article]
            [andrewslai.clj.routes.admin :as admin]
            [andrewslai.clj.utils :refer [parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context GET POST]]
            [ring.util.http-response :refer [ok not-found]]
            [clojure.spec.alpha :as s]
            [spec-tools.swagger.core :as swagger])
  (:import java.time.LocalDateTime))


(defn get-all-articles [db]
  (article/get-all-articles db))

(defn get-article [db article-name]
  (article/get-article db article-name))

(defn create-article! [db {:keys [timestamp] :as article}]
  (article/create-article! db (if (nil? timestamp)
                                (assoc article :timestamp (java.time.LocalDateTime/now))
                                article)))
