(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.utils.core :as utils]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-articles
  (rdbms/make-finder :articles))

(defn create-article!
  [db article]
  (let [now    (utils/now)
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
    (let [now    (utils/now)
          [{article-id :id :as article}] (if article-id
                                           (get-articles tx {:id article-id})
                                           (create-article! tx (select-keys article-branch [:author :article-url :article-tags :hostname])))
          [{branch-id :id :as branch}]   (rdbms/insert! tx
                                                        :article-branches {:branch-name branch-name
                                                                           :article-id  article-id
                                                                           :created-at  now
                                                                           :modified-at now}
                                                        :ex-subtype :UnableToCreateArticleBranch)
          result                         (get-branches tx {:branch-id branch-id})]
      (log/infof "Created Article Branch: %s" result)
      result)))

(defn publish-branch!
  ([db branch-id]
   (publish-branch! db branch-id (utils/now)))
  ([db branch-id now]
   (log/infof "Publishing Branch: %s" branch-id)
   (let [result (rdbms/update! db :article-branches
                               {:published-at now}
                               [:= :id branch-id]
                               :ex-subtype :UnableToPublishBranch)
         result (get-branches db {:branch-id branch-id})]
     (log/infof "Published Branch: %s" result)
     result)))

(defn unpublish-branch!
  [db branch-id]
  (log/infof "Unpublishing Branch: %s" branch-id)
  (let [result (rdbms/update! db :article-branches
                              {:published-at nil}
                              [:= :id branch-id]
                              :ex-subtype :UnableToUnPublishBranch)
        result (get-branches db {:branch-id branch-id})]
    (log/infof "Unpublished Branch: %s" result)
    result))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Versions
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-versions
  (rdbms/make-finder :full-versions))

(defn create-version!
  [db {:keys [branch-id] :as article-branch} {:keys [created-at] :as article-version}]
  (let [branch-id                      (or branch-id (get-in (get-branches db article-branch)
                                                             [0 :branch-id]))
        now                            (or created-at (utils/now))
        [{version-id :id :as version}] (rdbms/insert! db
                                                      :article-versions (assoc article-version
                                                                               :branch-id   branch-id
                                                                               :created-at  now
                                                                               :modified-at now)
                                                      :ex-subtype :UnableToCreateArticleBranch)

        result (get-versions db {:version-id version-id})]
    (log/infof "Created Article version: %s" result)
    result))

(defn published?
  [branch]
  (:published-at branch))

(defn new-version!
  [db article-branch article-version]
  (let [existing-branch (first (get-branches db (dissoc article-branch :hostname)))]
    (if (published? existing-branch)
      (throw (ex-info "Cannot change a published branch" existing-branch))
      (next/with-transaction [tx db]
        (when-not existing-branch
          (create-branch! tx article-branch))
        (let [[branch] (get-branches tx (dissoc article-branch :hostname))]
          (create-version! tx branch article-version))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Published articles
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-published-articles
  (rdbms/make-finder :published-articles))
