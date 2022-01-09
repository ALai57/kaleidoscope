(ns andrewslai.clj.entities.article
  (:require [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s]
            [andrewslai.clj.config :as config]))

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

  (require '[andrewslai.clj.config :as config])

  (def database
    (config/configure-database (System/getenv)))

  (get-all-articles database)

  (-> database
    (get-article "test-article")
    (clojure.pprint/pprint))

  (create-article! database {:title        "My test article"
                             :article_tags "thoughts"
                             :article_url  "my-test-article"
                             :author       "Andrew Lai"
                             :content      "<h1>Hello world!</h1>"
                             :article_id   13
                             :timestamp    (java.time.LocalDateTime/now)})
  )
