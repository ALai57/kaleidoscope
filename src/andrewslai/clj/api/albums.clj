(ns andrewslai.clj.api.albums
  (:require [andrewslai.clj.persistence.rdbms :as rdbms])
  (:import java.util.UUID))

(defn now []
  (java.time.LocalDateTime/now))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-albums
  (rdbms/make-finder :enhanced-albums))

(defn create-album!
  [database album]
  (rdbms/insert! database
                 :albums     (assoc album :id (UUID/randomUUID))
                 :ex-subtype :UnableToCreateAlbum))

(defn update-album!
  [database album]
  (rdbms/update! database
                 :albums     album
                 [:= :id (:id album)]
                 :ex-subtype :UnableToUpdateAlbum))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-photos
  (rdbms/make-finder :photos))

(defn create-photo!
  [database photo]
  (first (rdbms/insert! database
                        :photos     photo
                        :ex-subtype :UnableToCreatePhoto)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos in albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-photos-to-album! [database album-id photo-ids]
  (let [now-time        (now)
        photos-in-album (vec (for [photo-id (if (seq? photo-ids) photo-ids [photo-ids])]
                               {:id          (java.util.UUID/randomUUID)
                                :photo-id    photo-id
                                :album-id    album-id
                                :created-at  now-time
                                :modified-at now-time}))]
    (vec (rdbms/insert! database
                        :photos_in_albums photos-in-album
                        :ex-subtype :UnableToAddPhotoToAlbum))))

(defn remove-content-album-link!
  [database album-content-id]
  (rdbms/delete! database
                 :photos_in_albums album-content-id
                 :ex-subtype :UnableToDeletePhotoFromAlbum))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Album contents
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-album-contents
  (rdbms/make-finder :album-contents))
