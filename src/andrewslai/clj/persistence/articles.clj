(ns andrewslai.clj.persistence.articles
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.postgres :refer [pg-db]]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.walk :refer [keywordize-keys]]
            ))

(defprotocol ArticlePersistence
  (save-article! [_])
  (get-article-metadata [_ article-name])
  (get-article-content [_ article-id])
  (get-full-article [_ article-name])
  (get-all-articles [_])
  (get-resume-info [_]))



(defn- -get-all-articles [this]
  (try
    (rdbms/hselect (:database this) {:select [:*]
                                     :from [:articles]})
    (catch Exception e
      (str "get-all-articles caught exception: " (.getMessage e)))))

(defn- -get-article-metadata [this article-url]
  (try
    (first
      (rdbms/hselect (:database this)
                     {:select [:*]
                      :from [:articles]
                      :where [:= :articles/article_url article-url]}))
    (catch Exception e
      (str "get-article-metadata caught exception: " (.getMessage e)
           "postgres config: " (assoc (:database this) :password "xxxxxx")))))

(defn- -get-article-content [this article-id]
  (try
    (rdbms/hselect (:database this)
                   {:select [:article_id :content :dynamicjs]
                    :from [:content]
                    :where [:= :articles/article_id article-id]})
    (catch Exception e
      (str "get-content caught exception: " (.getMessage e)
           "postgres config: " (assoc (:database this):password "xxxxxx")))))

(defn -get-full-article [this article-name]
  (let [article (get-article-metadata this article-name)
        article-id (:article_id article)
        content (get-article-content this article-id)]
    {:article-name article-name
     :article (assoc-in article [:content] content)}))

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
           "postgres config: " (assoc (:database this) :password "xxxxxx")))))

(defn -save-article! [db]
  nil)

(defrecord ArticleDatabase [database]
  ArticlePersistence
  (save-article! [this]
    (-save-article! this))
  (get-article-metadata [this article-name]
    (-get-article-metadata this article-name))
  (get-article-content [this article-id]
    (-get-article-content this article-id))
  (get-full-article [this article-name]
    (-get-full-article this article-name))
  (get-all-articles [this]
    (-get-all-articles this))
  (get-resume-info [this]
    (-get-resume-info this)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (get-all-articles)
  (get-all-articles (->ArticleDatabase pg-db))
  (clojure.pprint/pprint (get-full-article (->ArticleDatabase pg-db) "test-article"))
  (clojure.pprint/pprint (get-article-content (->ArticleDatabase pg-db) 10))

  (get-all-articles (->ArticleDatabase pg-db))

  (clojure.pprint/pprint (:projects (get-resume-info (->ArticleDatabase pg-db))))

  (clojure.pprint/pprint (first (:organizations (get-resume-info (->ArticleDatabase pg-db)))))

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
