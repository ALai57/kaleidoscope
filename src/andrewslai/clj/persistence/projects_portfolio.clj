(ns andrewslai.clj.persistence.projects-portfolio
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.utils :refer [validate]]
            [andrewslai.clj.persistence.postgres :refer [pg-db]]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.spec.alpha :as s]
            [clojure.walk :refer [keywordize-keys]]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import java.time.LocalDateTime))

(defprotocol ProjectPortfolioPersistence
  (get-resume-info [_]))

(s/def ::id int?)
(s/def ::name string?)
(s/def ::url string?)
(s/def ::image_url string?)
(s/def ::description string?)

(s/def ::organization (s/keys :req-un [::id
                                       ::name
                                       ::url
                                       ::image_url
                                       ::description]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Resume info
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- -get-resume-info [this]
  (try
    (let [orgs (rdbms/hselect (:database this)
                              {:select [:*] :from [:organizations]})
          projects (rdbms/hselect (:database this)
                                  {:select [:*] :from [:projects]})
          skills (rdbms/hselect (:database this)
                                {:select [:*] :from [:skills]})]
      {:organizations orgs
       :projects projects
       :skills skills})
    (catch Exception e
      (str "get-resume-info caught exception: " (.getMessage e)
           #_#_"postgres config: " (assoc (:database this) :password "xxxxxx")))))

(defrecord ProjectPortfolioDatabase [database]
  ProjectPortfolioPersistence
  (get-resume-info [this]
    (-get-resume-info this)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (-> pg-db
      ->ProjectPortfolioDatabase
      get-resume-info
      :projects
      clojure.pprint/pprint)

  (-> pg-db
      ->ProjectPortfolioDatabase
      get-resume-info
      :organizations
      first
      clojure.pprint/pprint)

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
