(ns andrewslai.clj.persistence.articles
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.utils :refer [validate]]
            [andrewslai.clj.persistence.postgres :refer [pg-db]]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.spec.alpha :as s]
            [spec-tools.spec :as spec]
            [clojure.walk :refer [keywordize-keys]]
            [slingshot.slingshot :refer [throw+ try+]])
  (:import java.time.LocalDateTime))

(defprotocol ArticlePersistence
  (create-article! [_ article-payload])
  (get-article [_ article-name])
  (get-all-articles [_]))

(s/def ::article_id spec/integer?)
(s/def ::article_name spec/string?)
(s/def ::title spec/string?)
(s/def ::article_tags spec/string?)
(s/def ::timestamp (s/or :date spec/inst? :string spec/string?))
(s/def ::article_url spec/string?)
(s/def ::author spec/string?)
(s/def ::content spec/string?)

(s/def ::article (s/keys :req-un [::title
                                  ::article_tags
                                  ::author
                                  ::timestamp
                                  ::article_url
                                  ::article_id]
                         :opt-un [::content]))

(s/def ::articles (s/coll-of ::article))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Get all articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- -get-all-articles [this]
  (try
    (rdbms/hselect (:database this) {:select [:*]
                                     :from [:articles]})
    (catch Exception e
      (str "get-all-articles caught exception: " (.getMessage e)))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Article Metadata
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- -get-article-metadata [this article-url]
  (try
    (first
     (rdbms/hselect (:database this)
                    {:select [:*]
                     :from [:articles]
                     :where [:= :articles/article_url article-url]}))
    (catch Exception e
      (str "get-article-metadata caught exception: " (.getMessage e)
           #_#_"postgres config: " (assoc (:database this) :password "xxxxxx")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Full article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -get-article [this article_url]
  (validate ::article_url article_url :IllegalArgumentException)
  (try+
   (first
    (rdbms/hselect (:database this)
                   {:select [:*]
                    :from [:articles]
                    :where [:= :articles/article_url article_url]}))
   (catch Exception e
     (str "get-article-metadata caught exception: " (.getMessage e)
          #_#_"postgres config: " (assoc (:database this) :password "xxxxxx")))))


(defn -create-article! [this article-payload]
  (let [article (-> article-payload
                    (assoc :timestamp (java.time.LocalDateTime/now)))]
    (first (rdbms/insert! (:database this) "articles" article))))

(defrecord ArticleDatabase [database]
  ArticlePersistence
  (create-article! [this article-payload]
    (-create-article! this article-payload))
  (get-article [this article-name]
    (-get-article this article-name))
  (get-all-articles [this]
    (-get-all-articles this)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Functions to test DB connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(comment
  (get-all-articles)
  (get-all-articles (->ArticleDatabase pg-db))
  (clojure.pprint/pprint (get-full-article (->ArticleDatabase pg-db) "test-article"))
  (clojure.pprint/pprint (get-article-content (->ArticleDatabase pg-db) 10))

  (get-all-articles (->ArticleDatabase pg-db))

  )
