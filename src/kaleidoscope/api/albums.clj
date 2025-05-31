(ns kaleidoscope.api.albums
  (:require [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as u]
            [kaleidoscope.utils.core :as utils]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def get-albums
  (rdbms/make-finder :enhanced-albums))

(defn create-album!
  [database album]
  (rdbms/insert! database
                 :albums (assoc album :id (utils/uuid))
                 :ex-subtype :UnableToCreateAlbum))

(defn update-album!
  [database album]
  (rdbms/update! database
                 :albums album
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
                                      (:id query-map) (update :id (comp parse-uuid str))
                                      (:photo-id query-map) (update :photo-id (comp parse-uuid str))))))

(defn create-photo!
  [database photo]
  (first (rdbms/insert! database
                        :photos photo
                        :ex-subtype :UnableToCreatePhoto)))

(def get-photo-versions
  (rdbms/make-finder :photo_versions))

(defn update-photo!
  [database photo]
  (first (rdbms/update! database
                        :photos photo
                        :ex-subtype :UnableToUpdatePhoto)))

(defn create-photo-version!
  [database photo-version]
  (first (rdbms/insert! database
                        :photo-versions (assoc photo-version :id (utils/uuid))
                        :ex-subtype :UnableToCreatePhotoVersion)))

(def IMAGE-VERSIONS
  ;; category  wd   ht
  [:raw
   :thumbnail                                               ;;[100 100]
   :gallery                                                 ;;[165 165]
   :monitor                                                 ;;[1920 1080]
   :mobile                                                  ;;[1200 630]
   ])

(defn make-image-version
  [static-content-adapter photo-id extension image-version-name]
  (let [id (utils/uuid)
        image-category (name image-version-name)
        path (format "%s/%s/%s.%s" (:photos-folder static-content-adapter) photo-id image-category extension)]
    (-> {:image-category image-category
         :photo-id       photo-id
         :id             id
         :storage-driver (:storage-driver static-content-adapter)
         :storage-root   (:storage-root static-content-adapter)
         :path           path
         :filename       (format "%s.%s" image-category extension)})))

(defn- add-id [{:keys [id] :as photo-version}]
  (if id
    photo-version
    (assoc photo-version :id (utils/uuid))))

(defn create-photo-version-2!
  [database photo-versions]
  (span/with-span! {:name "kaleidoscope.api.photo-version.create"}
    ;;(log/infof "Creating photo version for %s" path)
    (log/infof "Creating photo versions %s" (count photo-versions))
    (rdbms/insert! database
                   :photo-versions (map add-id photo-versions)
                   :ex-subtype :UnableToCreatePhotoVersion)))

(defn new-image
  [{:keys [static-content-adapter database notify-image-resizer!] :as components}
   hostname
   {:keys [filename tempfile extension photo-id] :as file}]
  (let [photo-id (or photo-id (utils/uuid))
        _ (create-photo! database {:id photo-id :hostname hostname})

        versions (map (partial make-image-version static-content-adapter photo-id extension) IMAGE-VERSIONS)
        results (create-photo-version-2! database versions)]

    (fs/put-file static-content-adapter
                 (format "%s/%s/raw.%s" (:photos-folder static-content-adapter) photo-id extension)
                 (u/->file-input-stream tempfile)
                 (dissoc file :tempfile :file-input-stream))
    (try
      (let [image-path (format "s3://%s/media/%s/raw.%s" hostname photo-id extension)]
        (log/infof "Notifying image resizer for async processing of %s" image-path)
        (notify-image-resizer! :subject "image-resize-requested"
                               :message image-path
                               :message-attributes {"hostname" hostname "extension" extension}))
      (catch Throwable e
        (log/errorf "Caught error publishing to Image Notifier topic for image '%s'" filename)
        (log/error (ex-message e))
        (throw e)))
    {:photo-id photo-id
     :versions (vec results)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos in albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-photos-to-album! [database album-id photo-ids]
  (let [now-time (utils/now)
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
(def get-album-contents
  (rdbms/make-finder :album-contents))

(comment
  (def example-album-content
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
