(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.routes.admin :as admin]
            [andrewslai.clj.utils :refer [parse-body]]
            [buddy.auth.accessrules :refer [restrict]]
            [compojure.api.sweet :refer [context GET POST]]
            [ring.util.http-response :refer [ok not-found]]
            [clojure.spec.alpha :as s]
            [spec-tools.swagger.core :as swagger]))


(defn get-all-articles [db]
  (articles/get-all-articles db))

(defn get-article [db article-name]
  (articles/get-article db article-name))

(defn create-article! [db article]
  (articles/create-article! db article))
