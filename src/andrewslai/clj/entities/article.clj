(ns andrewslai.clj.entities.article
  (:require [andrewslai.clj.persistence.postgres2 :as pg]
            [clojure.spec.alpha :as s]
            [spec-tools.spec :as spec]))

(s/def :andrewslai.article/article_id spec/integer?)
(s/def :andrewslai.article/article_name spec/string?)
(s/def :andrewslai.article/title spec/string?)
(s/def :andrewslai.article/article_tags spec/string?)
(s/def :andrewslai.article/timestamp (s/or :date spec/inst? :string spec/string?))
(s/def :andrewslai.article/article_url spec/string?)
(s/def :andrewslai.article/author spec/string?)
(s/def :andrewslai.article/content spec/string?)

(s/def :andrewslai.article/article
  (s/keys :req-un [:andrewslai.article/title
                   :andrewslai.article/article_tags
                   :andrewslai.article/author
                   :andrewslai.article/timestamp
                   :andrewslai.article/article_url
                   :andrewslai.article/article_id]
          :opt-un [:andrewslai.article/content]))

(s/def :andrewslai.article/articles (s/coll-of :andrewslai.article/article))

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
              ;;:input-validation :andrewslai.article/article
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
