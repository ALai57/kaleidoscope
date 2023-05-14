(ns kaleidoscope.api.albums
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]
            [taoensso.timbre :as log]))

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

(def -get-full-photos
  (rdbms/make-finder :full_photos))

(defn get-full-photos
  ([database]
   (-get-full-photos database))
  ([database query-map]
   (-get-full-photos database (cond-> query-map
                                (:id query-map)       (update :id (comp parse-uuid str))
                                (:photo-id query-map) (update :photo-id (comp parse-uuid str))))))

(defn create-photo!
  [database photo]
  (let [now-time (utils/now)]
    (first (rdbms/insert! database
                          :photos     (assoc photo
                                             :created-at  now-time
                                             :modified-at now-time)
                          :ex-subtype :UnableToCreatePhoto))))

(def get-photo-versions
  (rdbms/make-finder :photo_versions))

(defn create-photo-version!
  [database photo-version]
  (let [now-time (utils/now)]
    (first (rdbms/insert! database
                          :photo-versions (assoc photo-version
                                                 :id          (utils/uuid)
                                                 :created-at  now-time
                                                 :modified-at now-time)
                          :ex-subtype     :UnableToCreatePhotoVersion))))

(comment
  (log/infof "Creating file `%s` with metadata:\n %s" images-path (-> metadata
                                                                      clojure.pprint/pprint
                                                                      with-out-str))
  )

(defn create-photo-version-2!
  [database
   {:keys [storage-root storage-driver photos-folder] :as static-content-adapter
    :or   {photos-folder "media"}}
   {:keys [file image-category photo-id] :as photo-version}]
  (let [id       (utils/uuid)
        now-time (utils/now)
        filename (format "%s.%s" image-category (:extension file))
        path     (format "%s/%s/%s" photos-folder photo-id filename)
        metadata (dissoc file :tempfile :file-input-stream)

        _         (log/infof "Creating photo version for %s" path)

        db-result (rdbms/insert! database
                                 :photo-versions (-> photo-version
                                                     (select-keys [:photo-id :image-category])
                                                     (assoc
                                                      :id             id
                                                      :storage-driver storage-driver
                                                      :storage-root   storage-root
                                                      :path           path
                                                      :filename       filename
                                                      :created-at     now-time
                                                      :modified-at    now-time))
                                 :ex-subtype     :UnableToCreatePhotoVersion)]
    (fs/put-file static-content-adapter
                 path
                 (:file-input-stream file)
                 metadata)
    db-result
    ))

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
(s/def :kaleidoscope.albums.contents/album-id uuid?)
(s/def :kaleidoscope.albums.contents/album-content-id uuid?)
(s/def :kaleidoscope.albums.contents/added-to-album-at inst?)
(s/def :kaleidoscope.albums.contents/photo-id uuid?)
(s/def :kaleidoscope.albums.contents/photo-src string?)
(s/def :kaleidoscope.albums.contents/photo-title string?)
(s/def :kaleidoscope.albums.contents/album-name string?)
(s/def :kaleidoscope.albums.contents/album-description string?)
(s/def :kaleidoscope.albums.contents/cover-photo-id uuid?)
(s/def :kaleidoscope.albums.contents/cover-photo-src string?)

(s/def :kaleidoscope.albums.contents/album-content
  (s/keys :req-un [:kaleidoscope.albums.contents/album-id
                   :kaleidoscope.albums.contents/album-content-id
                   :kaleidoscope.albums.contents/added-to-album-at
                   :kaleidoscope.albums.contents/photo-id
                   :kaleidoscope.albums.contents/photo-src
                   :kaleidoscope.albums.contents/photo-title
                   :kaleidoscope.albums.contents/album-name
                   :kaleidoscope.albums.contents/album-description
                   :kaleidoscope.albums.contents/cover-photo-id
                   :kaleidoscope.albums.contents/cover-photo-src]))

(s/def :kaleidoscope.albums.contents/album-contents
  (s/coll-of :kaleidoscope.albums.contents/album-content))

(def get-album-contents
  (rdbms/make-finder :album-contents))


(comment
  ;; Example album content
  (s/explain-str :kaleidoscope.albums.contents/album-content
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
