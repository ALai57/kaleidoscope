(ns andrewslai.clj.api.articles
  (:require [andrewslai.clj.entities.article :as article]
            [clojure.set :as set]
            [taoensso.timbre :as log])
  (:import java.time.LocalDateTime))

;; Articles
(def get-all-articles article/get-all-articles)
(def get-article article/get-article)
(defn create-article! [db article]
  (let [now    (java.time.LocalDateTime/now)
        result (article/create-article! db (assoc article
                                                  :created-at  now
                                                  :modified-at now))]
    (log/infof "Created Article: %s" result)
    result))

;; Branches
(defn new-branch!
  [db article branch]
  (let [{article-id :id :as article} (or (get-article db (:article-url article))
                                         (create-article! db article))
        branch                       (or (article/get-article-branch-by-name db article-id (:branch-name branch))
                                         (article/create-article-branch! db (assoc branch
                                                                                   :article-id article-id)))
        result                       (merge article
                                            (set/rename-keys branch {:id :branch-id})
                                            {:article-id article-id})]
    (log/infof "Created Article Branch: %s" result)
    result))

(def get-article-branches-by-url article/get-article-branches-by-url)
(def get-all-branches article/get-all-branches)
(def get-branch article/get-branch)

;; Versions/commits
(def get-article-versions article/get-article-versions)

;; Compositions
(def get-published-articles article/get-published-articles)
(def get-published-article article/get-published-article)
(def get-published-article-by-url article/get-published-article-by-url)
