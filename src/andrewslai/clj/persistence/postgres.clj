(ns andrewslai.clj.persistence.postgres
  (:require [andrewslai.clj.persistence.core :refer [Persistence]]
            [andrewslai.clj.env :as env]
            [clojure.java.jdbc :as sql]))


(def db-port (@env/env :db-port))
(def db-host (@env/env :db-host))
(def db-name (@env/env :db-name))
(def db-user (@env/env :db-user))
(def db-password (@env/env :db-password))
(def live-db? (@env/env :live-db?))

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

(defn make-db []
  (reify Persistence
    (save-article! [_]
      nil)
    (get-article [_]
      nil)
    (get-all-articles [_]
      (get-all-articles))
    (get-article-metadata [_]
      nil)))

(comment
  (get-all-articles)
  )
