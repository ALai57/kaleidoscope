(ns andrewslai.clj.persistence.postgres
  (:require [andrewslai.clj.persistence.core :refer [Persistence] :as db2]
            [andrewslai.clj.env :as env]
            [clojure.java.jdbc :as sql]))


(def db-port (@env/env :db-port))
(def db-host (@env/env :db-host))
(def db-name (@env/env :db-name))
(def db-user (@env/env :db-user))
(def db-password (@env/env :db-password))

(def pg-db {:dbtype "postgresql"
            :dbname db-name
            :host db-host
            :user db-user
            :password db-password})

(defn- get-all-articles []
  (try
    (sql/query pg-db
               [(str "SELECT * FROM articles ORDER BY timestamp DESC")])
    (catch Exception e
      (str "get-all-articles caught exception: " (.getMessage e)))))

(defn- get-article-metadata [article-name]
  (try
    (first
      (sql/query pg-db
                 [(str "SELECT * FROM articles"
                       " WHERE article_url = ?") article-name]))
    (catch Exception e
      (str "get-article caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(defn- get-article-content [article-id]
  (try
    (sql/query pg-db
               [(str "SELECT "
                     "article_id, content, dynamicjs "
                     "FROM content "
                     "WHERE article_id = ?") article-id])
    (catch Exception e
      (str "get-content caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(defn- get-full-article [article-name]
  (let [article (get-article-metadata article-name)
        article-id (:article_id article)
        content (get-article-content article-id)]
    (assoc-in article [:content] content)))

(defn make-db []
  (reify Persistence
    (save-article! [_]
      nil)
    (get-article [_]
      nil)
    (get-full-article [_ article-name]
      (get-full-article article-name))
    (get-all-articles [_]
      (get-all-articles))))

(comment
  (get-all-articles)
  (db2/get-all-articles (make-db))
  (db2/get-full-article (make-db) "test-article")
  (db2/get-article-content (make-db) 1)
  )
