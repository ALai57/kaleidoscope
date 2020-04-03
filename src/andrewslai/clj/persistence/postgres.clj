(ns andrewslai.clj.persistence.postgres
  (:require [andrewslai.clj.persistence.core :refer [Persistence] :as db]
            [andrewslai.clj.env :as env]
            [andrewslai.clj.auth.crypto :as encryption]
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

(defn- get-resume-info []
  (try
    (let [organizations (sql/query pg-db
                                   [(str "SELECT *"
                                         "FROM organizations ")])
          projects (sql/query pg-db
                              [(str "SELECT *"
                                    "FROM projects ")])
          skills (sql/query pg-db
                            [(str "SELECT *"
                                  "FROM skills ")])]
      {:organizations organizations
       :projects projects
       :skills skills})
    (catch Exception e
      (str "get-resume-info caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

;;https://www.donedone.com/building-the-optimal-user-database-model-for-your-application/
(defn- create-user! [{:keys [username email first_name last_name password] :as user}]
  (try
    (let [id (java.util.UUID/randomUUID)
          result (sql/with-db-transaction [conn pg-db]
                   (sql/insert! pg-db "users" {:id id
                                               :username username
                                               :email email})
                   (sql/insert! pg-db "logins"
                                {:id id
                                 :hashed_password
                                 (encryption/encrypt (encryption/make-encryption)
                                                     password)}))
          ;;privileges (sql/insert! pg-db "privileges")
          ]
      #_(println "Insert successful!" result))
    (catch Exception e
      (str "create-user! caught exception: " (.getMessage e)
           "postgres config: " (assoc pg-db :password "xxxxxx")))))

(comment

  (sql/db-do-commands pg-db [(slurp "./scripts/db/setup_rbac/setup_users.sql")
                             (slurp "./scripts/db/setup_rbac/setup_logins.sql")
                             (slurp "./scripts/db/setup_rbac/setup_roles.sql")
                             (slurp "./scripts/db/setup_rbac/setup_permissions.sql")
                             (slurp "./scripts/db/setup_rbac/setup_roles_permissions.sql")
                             (slurp "./scripts/db/setup_rbac/add_user_role.sql")])

  (sql/db-do-commands pg-db [(slurp "./scripts/db/setup_rbac/delete_logins.sql")
                             (slurp "./scripts/db/setup_rbac/delete_users.sql")
                             (slurp "./scripts/db/setup_rbac/delete_roles_permissions.sql")
                             (slurp "./scripts/db/setup_rbac/delete_roles.sql")
                             (slurp "./scripts/db/setup_rbac/delete_permissions.sql")])


  (sql/query pg-db ["SELECT * FROM users"])
  (sql/query pg-db ["SELECT * FROM logins"])
  (sql/query pg-db ["SELECT * FROM roles"])
  (sql/query pg-db ["SELECT * FROM permissions"])

  (create-user! {:username "andrewlai"
                 :email "andrewlai@andrewlai.com"
                 :first_name "andrew"
                 :last_name "lai"
                 :password "mypassword"})
  )

(defn- get-password [user-id]
  nil)

(defn make-db []
  (reify Persistence
    (save-article! [_]
      nil)
    (get-full-article [_ article-name]
      (get-full-article article-name))
    (get-all-articles [_]
      (get-all-articles))
    (get-resume-info [_]
      (get-resume-info))
    (create-user! [_ user]
      (create-user! user))
    (get-password [_ user-id]
      (get-password user-id))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (get-all-articles)
  (db/get-all-articles (make-db))
  (clojure.pprint/pprint (db/get-full-article (make-db) "test-article"))

  (db/get-article-content (make-db) 1)

  (clojure.pprint/pprint (:projects (db/get-resume-info (make-db))))

  (clojure.pprint/pprint (first (:skills (db/get-resume-info (make-db)))))

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
