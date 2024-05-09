(ns kaleidoscope.api.audiences
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.api.articles :as articles-api]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Audiences:
;; - Attach a group to an article
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-article-audiences
  (rdbms/make-finder :full-article-audiences))

(defn- -add-audiences-to-article!
  [db article-id group-ids]
  (log/infof "Adding an audience to article %s" article-id)
  (let [now (utils/now)]
    (rdbms/insert! db
                   :article-audiences (for [group-id group-ids
                                            :let     [id (utils/uuid)]]
                                        {:id         id
                                         :group-id   group-id
                                         :article-id article-id
                                         :created-at now})
                   :ex-subtype :UnableToAddArticleAudience)))

(defn add-audience-to-article!
  [db article group]
  ;; TODO: only go to DB after we know we have to
  (let [existing-audience (get-article-audiences db {:article-id (:id article)
                                                     :group-id   (:id group)})]
    (cond
      (nil? (:hostname article))                      (log/warnf "Article is missing hostname %s" article)
      (empty? (articles-api/get-articles db article)) (log/warnf "No articles matching %s" article)

      ;; Only attempt to create an audience if it doesn't exist.
      ;; This is a workaround so I don't have to deal with a multiple upsert in both
      ;; HSQL and Postgres
      (not-empty existing-audience) existing-audience
      (empty? existing-audience)    (-add-audiences-to-article! db (:id article) [(:id group)]))))

(defn delete-article-audience!
  [database audience-id]
  (log/infof "Deleting Article Audience: %s" audience-id)
  (rdbms/delete! database
                 :article-audiences audience-id
                 :ex-subtype :UnableToDeleteArticleAudience))
