(ns kaleidoscope.api.tags
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Tags
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-tags
  (rdbms/make-finder :tags))

(defn create-tag!
  [db {:keys [name hostname] :as tag}]
  (log/infof "Creating Tag: %s" tag)
  (let [now    (utils/now)
        result (rdbms/insert! db
                              :tags (assoc tag
                                           :created-at  now
                                           :modified-at now)
                              :ex-subtype :UnableToCreateTag)]
    (log/infof "Created Tag: %s" result)
    result))

(defn delete-tag!
  [database tag-id]
  (log/infof "Deleting Tag: %s" tag-id)
  (rdbms/delete! database
                 :tags tag-id
                 :ex-subtype :UnableToDeleteTag))

(defn update-tag!
  [database {:keys [id] :as tag}]
  (log/infof "Updating Tag: %s" tag)
  (rdbms/update! database
                 :tags tag
                 [:= :id (:id tag)]
                 :ex-subtype :UnableToUpdateTag))
