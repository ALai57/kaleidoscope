(ns andrewslai.clj.entities.album
  (:require [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s])
  (:import [java.util UUID]))

(defn now []
  (java.time.LocalDateTime/now))

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

;; Album contents
(defn add-photos-to-album! [database album-id photo-ids]
  (let [now-time (now)]
    (vec (pg/insert! database
                     :photos_in_albums (vec (for [photo-id photo-ids]
                                              {:id          (java.util.UUID/randomUUID)
                                               :photo-id    photo-id
                                               :album-id    album-id
                                               :created-at  now-time
                                               :modified-at now-time}))
                     :ex-subtype :UnableToAddPhotoToAlbum))))

(defn remove-content-from-album! [database album-id album-content-id]
  (first (pg/delete! database
                     :photos_in_albums album-content-id
                     :ex-subtype :UnableToDeletePhotoFromAlbum)))

(defn get-album-content [database album-id album-content-id]
  (first (pg/select database
                    {:select [:*]
                     :from   [:album_contents]
                     :where  [:and
                              [:= :album_contents/album-id album-id]
                              [:= :album_contents/album-content-id album-content-id]]})))

(defn get-album-contents [database album-id]
  (pg/select database
             {:select [:*]
              :from   [:album_contents]
              :where  [:= :album_contents/album-id album-id]}))
