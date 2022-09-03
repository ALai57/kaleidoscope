(ns andrewslai.clj.entities.article
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s]))

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
