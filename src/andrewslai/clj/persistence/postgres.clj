(ns andrewslai.clj.persistence.postgres
  (:require [andrewslai.clj.persistence.core :refer [Persistence] :as db]
            [andrewslai.clj.env :as env]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.walk :refer [keywordize-keys]])
  (:import (org.postgresql.util PGobject)))

(extend-protocol sql/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj conn metadata]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (keywordize-keys
                 (json/parse-string value))
        :else value))))

(extend-protocol sql/IResultSetReadColumn
  org.postgresql.jdbc.PgArray
  (result-set-read-column [pgobj metadata i]
    (vec (.getArray pgobj))))

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

(defn- get-all-articles [db]
  (try
    (sql/query (:conn db)
               [(str "SELECT * FROM articles ORDER BY timestamp DESC")])
    (catch Exception e
      (str "get-all-articles caught exception: " (.getMessage e)))))

(defn- get-article-metadata [db article-name]
  (try
    (first
      (sql/query (:conn db)
                 [(str "SELECT * FROM articles"
                       " WHERE article_url = ?") article-name]))
    (catch Exception e
      (str "get-article caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(defn- get-article-content [db article-id]
  (try
    (sql/query (:conn db)
               [(str "SELECT "
                     "article_id, content, dynamicjs "
                     "FROM content "
                     "WHERE article_id = ?") article-id])
    (catch Exception e
      (str "get-content caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(defn get-full-article [db article-name]
  (let [article (db/get-article-metadata db article-name)
        article-id (:article_id article)
        content (db/get-article-content db article-id)]
    {:article-name article-name
     :article (assoc-in article [:content] content)}))

(defn- get-resume-info [db]
  (try
    (let [organizations (sql/query (:conn db)
                                   [(str "SELECT *"
                                         "FROM organizations ")])
          projects (sql/query (:conn db)
                              [(str "SELECT *"
                                    "FROM projects ")])
          skills (sql/query (:conn db)
                            [(str "SELECT *"
                                  "FROM skills ")])]
      {:organizations organizations
       :projects projects
       :skills skills})
    (catch Exception e
      (str "get-resume-info caught exception: " (.getMessage e)
           "postgres config: " (assoc (:conn db) :password "xxxxxx")))))

;;https://www.donedone.com/building-the-optimal-user-database-model-for-your-application/
(defn- create-user! [db {:keys [username email first_name last_name password] :as user}]
  (try
    (let [id (java.util.UUID/randomUUID)
          result (sql/with-db-transaction [conn (:conn db)]
                   (sql/insert! (:conn db) "users" {:id id
                                                    :first_name first_name
                                                    :last_name last_name
                                                    :username username
                                                    :email email
                                                    :role_id 2})
                   (sql/insert! (:conn db) "logins"
                                {:id id
                                 :hashed_password
                                 (encryption/encrypt (encryption/make-encryption)
                                                     password)}))]
      #_(println "Insert successful!" result))
    (catch Exception e
      (str "create-user! caught exception: " (.getMessage e)
           "postgres config: " (assoc (:conn db) :password "xxxxxx")))))

(defn save-article! [db]
  nil)

(defrecord Database [conn]
  Persistence
  (save-article! [this]
    (save-article! this))
  (get-article-metadata [this article-name]
    (get-article-metadata this article-name))
  (get-article-content [this article-id]
    (get-article-content this article-id))
  (get-full-article [this article-name]
    (get-full-article this article-name))
  (get-all-articles [this]
    (get-all-articles this))
  (get-resume-info [this]
    (get-resume-info this))
  (create-user! [this user]
    (create-user! this user)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (get-all-articles)
  (db/get-all-articles (->Database pg-db))
  (clojure.pprint/pprint (db/get-full-article (->Database pg-db) "test-article"))
  (clojure.pprint/pprint (db/get-article-content (->Database pg-db) 10))

  (db/get-all-articles (->Database pg-db))

  (clojure.pprint/pprint (:projects (db/get-resume-info (->Database pg-db))))

  (clojure.pprint/pprint (first (:organizations (db/get-resume-info (->Database pg-db)))))

  (sql/query pg-db ["SELECT name FROM projects "])


  )


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; For uploading data to SQL databases
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (require '[clojure.data.csv :as csv])
  (require '[clojure.java.io :as io])

  (defn read-csv-file [file-name]
    (with-open [reader (io/reader file-name)]
      (let [parse-first-row (fn [csv]
                              [(map keyword (first csv)) (rest csv)])
            [first-row rows] (parse-first-row (doall (csv/read-csv reader)))]
        (map #(apply hash-map (interleave first-row %)) rows))))

  ;; Dangerous!! Deletes all entries and repopulates
  (defn repopulate-db [file-name table]
    (let [convert-to-int (fn [field r]
                           (map #(update %1 field (fn [x] (Integer/parseInt (field %)))) r))

          dirty-rows (read-csv-file file-name)
          rows (convert-to-int :id dirty-rows)]
      (sql/delete! pg-db table [])
      (sql/insert-multi! pg-db table rows)))

  (repopulate-db "/home/alai/dev/andrewslai/scripts/db/resume_cards/organizations.csv"
                 :organizations)

  (repopulate-db "/home/alai/dev/andrewslai/scripts/db/resume_cards/skills.csv"
                 :skills)
  )


(comment
  ;; TODO: clean this up
  (defn repopulate-db-projects [file-name table]
    (let [convert-to-int (fn [field r]
                           (map #(update %1 field (fn [x] (Integer/parseInt (field %)))) r))
          convert-to-map (fn [field r]
                           (map #(update %1 field (fn [x] (json/parse-string (field %)))) r))
          convert-to-pg (fn [field r]
                          (map #(update %1 field (fn [x] (map json/generate-string (field %)))) r))
          convert-to-array (fn [field r]
                             (map #(update %1 field (fn [x] (read-string (field %)))) r))

          dirty-rows (read-csv-file file-name)
          rows (->> dirty-rows
                    (convert-to-int :id)
                    (convert-to-array :organization_names)
                    (convert-to-array :skills_names)
                    (convert-to-pg :skills_names))]
      (sql/delete! pg-db table [])
      (sql/insert-multi! pg-db table rows)
      rows))

  (clojure.pprint/pprint (repopulate-db-projects "/home/alai/dev/andrewslai/scripts/db/resume_cards/projects.csv" :projects))

  )
