(ns andrewslai.clj.entities.article
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s]))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Low level operations
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-all-articles [database]
  (rdbms/select database {:select [:*]
                          :from [:articles]}))

(defn get-article [database article-url]
  (rdbms/select-one database
                    {:select [:*]
                     :from   [:articles]
                     :where  [:= :articles/article-url article-url]}))

(defn create-article! [database article]
  (rdbms/insert! database
                 :articles article
                 :ex-subtype :UnableToCreateArticle))

(defn create-article-branch! [database article-branch]
  (rdbms/insert! database
                 :article-branches article-branch
                 :ex-subtype :UnableToCreateArticleBranch))

(defn create-version! [database article-version]
  (rdbms/insert! database
                 :article-versions article-version
                 :ex-subtype :UnableToCreateArticleVersion))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reading using views
;; This allows us to get denormalized views of the data
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn get-all-branches [database]
  (rdbms/select database {:select [:*]
                          :from   [:full-branches]}))

(defn get-article-branches [database article-id]
  (rdbms/select database
                {:select [:*]
                 :from   [:full-branches]
                 :where  [:= :full-branches/article-id article-id]}))

(defn get-all-versions [database]
  (rdbms/select database {:select [:*]
                          :from   [:full-versions]}))

(defn get-branch-versions [database branch-id]
  (rdbms/select database
                {:select [:*]
                 :from   [:full-versions]
                 :where  [:= :full-versions/branch-id branch-id]}))

(defn get-article-versions [database article-id]
  (rdbms/select database {:select [:*]
                          :from   [:full-versions]
                          :where  [:= :full-versions/article-id article-id]}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  (require '[andrewslai.clj.init.config :as config])

  (def database
    (config/configure-database (System/getenv)))

  (get-all-articles database)

  (-> database
      (get-article "test-article")
      (clojure.pprint/pprint))

  (create-article! database {:id   13
                             :title        "My test article"
                             :article_tags "thoughts"
                             :article_url  "my-test-article"
                             :author       "Andrew Lai"
                             :content      "<h1>Hello world!</h1>"
                             :timestamp    (java.time.LocalDateTime/now)})
  )
