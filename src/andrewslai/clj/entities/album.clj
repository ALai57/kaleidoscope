(ns andrewslai.clj.entities.album
  (:require [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s])
  (:import [java.util UUID]))

(defn get-all-albums [database]
  (pg/select database {:select [:*]
                       :from   [:enhanced_albums]}))

;; TODO:
;; Don't allow user to specify ID
(defn create-album! [database album]
  (let [id (UUID/randomUUID)]
    (first (pg/insert! database
                       :albums     (assoc album :id id)
                       :ex-subtype :UnableToCreateAlbum))))

(defn get-album-by-id [database album-id]
  (first (pg/select database {:select [:*]
                              :from   [:enhanced_albums]
                              :where  [:= :albums/id album-id]})))

(defn get-album [database album-name]
  (first (pg/select database {:select [:*]
                              :from   [:enhanced_albums]
                              :where  [:= :albums/album-name album-name]})))

(defn update-album! [database album]
  (first (pg/update! database
                     :albums     album
                     [:= :id (:id album)]
                     :ex-subtype :UnableToUpdateAlbum)))
