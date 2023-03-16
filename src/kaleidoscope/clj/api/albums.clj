(ns kaleidoscope.clj.api.albums
  (:require [kaleidoscope.clj.persistence.rdbms :as rdbms]
            [kaleidoscope.clj.utils.core :as utils]
            [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-albums
  (rdbms/make-finder :enhanced-albums))

(defn create-album!
  [database album]
  (let [now (utils/now)]
    (rdbms/insert! database
                   :albums     (assoc album
                                      :id          (utils/uuid)
                                      :created-at  now
                                      :modified-at now)
                   :ex-subtype :UnableToCreateAlbum)))

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
  (let [now-time        (utils/now)
        photos-in-album (vec (for [photo-id (if (seq? photo-ids) photo-ids [photo-ids])]
                               {:id          (utils/uuid)
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
(s/def :andrewslai.albums.contents/album-id uuid?)
(s/def :andrewslai.albums.contents/album-content-id uuid?)
(s/def :andrewslai.albums.contents/added-to-album-at inst?)
(s/def :andrewslai.albums.contents/photo-id uuid?)
(s/def :andrewslai.albums.contents/photo-src string?)
(s/def :andrewslai.albums.contents/photo-title string?)
(s/def :andrewslai.albums.contents/album-name string?)
(s/def :andrewslai.albums.contents/album-description string?)
(s/def :andrewslai.albums.contents/cover-photo-id uuid?)
(s/def :andrewslai.albums.contents/cover-photo-src string?)

(s/def :andrewslai.albums.contents/album-content
  (s/keys :req-un [:andrewslai.albums.contents/album-id
                   :andrewslai.albums.contents/album-content-id
                   :andrewslai.albums.contents/added-to-album-at
                   :andrewslai.albums.contents/photo-id
                   :andrewslai.albums.contents/photo-src
                   :andrewslai.albums.contents/photo-title
                   :andrewslai.albums.contents/album-name
                   :andrewslai.albums.contents/album-description
                   :andrewslai.albums.contents/cover-photo-id
                   :andrewslai.albums.contents/cover-photo-src]))

(s/def :andrewslai.albums.contents/album-contents
  (s/coll-of :andrewslai.albums.contents/album-content))

(def get-album-contents
  (rdbms/make-finder :album-contents))


(comment
  ;; Example album content
  (s/explain-str :andrewslai.albums.contents/album-content
                 {:added-to-album-at #inst "2022-11-01T01:48:16.144313000-00:00",
                  :photo-src         "https://caheriaguilar.and.andrewslai.com/media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg",
                  :album-name        "My first album",
                  :cover-photo-id    #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4",
                  :album-description "This is the first album I made.",
                  :photo-title       "My first photo",
                  :cover-photo-src   "https://caheriaguilar.and.andrewslai.com/media/processed/1d675bdc-e6ec-4522-8920-4950d33d4eee-500.jpg",
                  :album-id          #uuid "7c72e23f-6cfe-4f75-adcf-adc39a758dc6",
                  :photo-id          #uuid "4a3db5ec-358c-4e36-9f19-3e0193001ff4",
                  :album-content-id  #uuid "96cdbf6a-f874-4f87-a0d8-ead500ad147d"})
  )
