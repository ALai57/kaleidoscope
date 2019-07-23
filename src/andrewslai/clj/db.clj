(ns andrewslai.clj.db
  (:require [cheshire.core :as json]
            [andrewslai.clj.env :as env]
            [clojure.java.jdbc :as sql]
            [clojure.walk :refer [keywordize-keys]]))


(import 'org.postgresql.util.PGobject)
(extend-protocol sql/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj conn metadata]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (keywordize-keys
                (json/parse-string value))
        :else value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Default
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def db-port (@env/env :db-port))
(def db-host (@env/env :db-host))
(def db-name (@env/env :db-name))
(def db-user (@env/env :db-user))
(def db-password (@env/env :db-password))
(def live-db? (@env/env :live-db?))
(def ssl-factory (@env/env :ssl-factory))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database Connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def pg-db {:dbtype "postgresql"
            :dbname db-name
            :host db-host
            :user db-user
            :password db-password})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- get-article-metadata [article-name]
  (try
    (first
     (sql/query pg-db
                [(str "SELECT * FROM articles"
                      " WHERE article_url = ?") article-name]))
    (catch Exception e
      (str "get-article caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(defn- get-recent-articles [n]
  (try
    (sql/query pg-db
               [(str "SELECT * FROM articles"
                     " LIMIT ?") n])
    (catch Exception e
      (str "get-recent-articles caught exception: " (.getMessage e)))))

(defn- get-article-content [article-id]
  (try
    (sql/query pg-db
               [(str "SELECT "
                     "article_id, metadata, content_order, "
                     "content_type, content "
                     "FROM content "
                     "WHERE article_id = ?") article-id])
    (catch Exception e
      (str "get-content caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(defn get-article-id [article-name]
  (-> (get-article-metadata article-name)
      first
      :article_id))

(defn get-full-article [article-name]
  (let [article (get-article-metadata article-name)
        article-id (:article_id article)
        content (get-article-content article-id)]
    (assoc-in article [:content] content)))

(comment

  (get-full-article "neural-network-explode-equation")
  (get-article-content 2)
  (:article_id (get-article-metadata "my-first-article"))

  (:metadata
   (first
    (get-in
     (get-full-article "my-first-article")
     [:content])))

  (clojure.pprint/pprint
   (get-in
    (get-content (first (get-article "my-first-article")))
    [:content]))
  (clojure.pprint/pprint sql/IResultSetReadColumn)

  (defn create-table [table specs]
    (sql/db-do-commands pg-db [(sql/create-table-ddl table specs)]))

  (defn insert [table data]
    (sql/insert-multi! pg-db table data))

  (defn select-all [table]
    (sql/query pg-db [(str "SELECT * FROM " (name table))]))

  )


#_(defn get-content [{:keys [article_id] :as article}]
    (try
      (let [content
            (sql/query pg-db
                       [(str "SELECT "
                             "article_id, metadata, content_order, "
                             "content_type, content "
                             "FROM content "
                             "WHERE article_id = ?") article_id])]
        (assoc-in article [:content] content))
      (catch Exception e
        (str "get-content caught exception: " (.getMessage e)
             "postgres config: " (assoc pg-db :password "xxxxxx")))))
