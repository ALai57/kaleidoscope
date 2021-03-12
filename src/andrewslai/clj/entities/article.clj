(ns andrewslai.clj.entities.article
  (:require [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s]))

(defn get-all-articles [database]
  (pg/select database {:select [:*]
                       :from [:articles]}))

(defn get-article [database article_url]
  (first
   (pg/select database
              {:select [:*]
               :from [:articles]
               :where [:= :articles/article_url article_url]})))

(defn create-article! [database article]
  (pg/insert! database
              :articles article
              :ex-subtype :UnableToCreateArticle))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment

  (require '[andrewslai.clj.dev-tools :as tools])

  (get-all-articles (tools/postgres-db))
  (clojure.pprint/pprint (get-article (tools/postgres-db) "test-article"))

  (create-article! (tools/postgres-db)
                   {:title "My test article"
                    :article_tags "thoughts"
                    :article_url "my-test-article"
                    :author "Andrew Lai"
                    :content "<h1>Hello world!</h1>"
                    :article_id 13
                    :timestamp (java.time.LocalDateTime/now)})
  )
