(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [clojure.set :as set]
            [next.jdbc :as next]
            [taoensso.timbre :as log])
  (:import java.time.LocalDateTime))

;; TODO: Find and Search functions

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-articles
  (rdbms/make-finder :articles))

(defn create-article!
  [db article]
  (let [now    (java.time.LocalDateTime/now)
        result (rdbms/insert! db
                              :articles (assoc article
                                               :created-at  now
                                               :modified-at now)
                              :ex-subtype :UnableToCreateArticle)]
    (log/infof "Created Article: %s" result)
    result))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Branches
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-branches
  (rdbms/make-finder :full-branches))

(defn create-branch!
  [db {:keys [article-id author branch-name] :as article-branch}]
  (next/with-transaction [tx db]
    (let [[{article-id :id :as article}] (if article-id
                                           (get-articles tx {:id article-id})
                                           (create-article! tx (select-keys article-branch [:author :article-url :article-tags])))
          [{branch-id :id :as branch}]   (rdbms/insert! tx
                                                        :article-branches {:branch-name branch-name
                                                                           :article-id  article-id}
                                                        :ex-subtype :UnableToCreateArticleBranch)
          result                         (get-branches tx {:branch-id branch-id})]
      (log/infof "Created Article Branch: %s" result)
      result)))

(defn publish-branch!
  ([db branch-id]
   (publish-branch! db branch-id (java.time.LocalDateTime/now)))
  ([db branch-id now]
   (let [result (rdbms/update! db :article-branches
                               {:published-at now}
                               [:= :id branch-id]
                               :ex-subtype :UnableToPublishBranch)
         result (get-branches db {:branch-id branch-id})]
     (log/infof "Published Branch: %s" result)
     result)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Versions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-versions
  (rdbms/make-finder :full-versions))

(defn create-version!
  [db {:keys [created-at] :as article-version}]
  (let [now                            (or created-at (java.time.LocalDateTime/now))
        [{version-id :id :as version}] (rdbms/insert! db
                                                      :article-versions (assoc article-version
                                                                               :created-at  now
                                                                               :modified-at now)
                                                      :ex-subtype :UnableToCreateArticleBranch)
        result                         (get-versions db {:version-id version-id})]
    (log/infof "Created Article version: %s" result)
    result))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Published articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-published-articles
  (rdbms/make-finder :published-articles))


(comment

  (require '[andrewslai.clj.init.config :as config])

  (def database
    (config/configure-database (System/getenv)))

  (get-articles database)

  (create-article! database {:article_tags "thoughts"
                             :article_url  "my-test-article"
                             :author       "Andrew Lai"})
  )
