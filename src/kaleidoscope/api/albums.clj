(ns kaleidoscope.api.albums
  (:require [kaleidoscope.api.resize :as resize]
            [kaleidoscope.persistence.filesystem :as fs]
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
  (let [now (utils/now)]
    (rdbms/insert! database
                   :albums (assoc album
                             :id (utils/uuid)
                             :created-at now
                             :modified-at now)
                   :ex-subtype :UnableToCreateAlbum)))

(defn update-album!
  "Update an album, scoped to hostname so a writer on one site cannot edit
  another site's album by id (same IDOR class as update-photo!/update-theme!).
  Returns the updated row, or nil if no album with that id exists for the host."
  [database hostname {:keys [id] :as album}]
  (rdbms/scoped-update! database
                        :albums
                        {:id id :hostname hostname}
                        (dissoc album :id :hostname)
                        :ex-subtype :UnableToUpdateAlbum))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def MEDIA-FOLDER
  "The intrinsic key prefix for every media object: media/<photo-id>/<cat>.<ext>.
  A fixed constant, not read off the store — the key is a pure function of the
  object's identity, never of which tenant/bucket/env serves it (see PLAN.md)."
  "media")

(defn sha256-hex
  "Lowercase hex SHA-256 of a byte array. Bytes are masked to unsigned before
  formatting so no byte renders as a sign-extended multi-digit value."
  [^bytes ba]
  (let [digest (.digest (java.security.MessageDigest/getInstance "SHA-256") ba)]
    (apply str (map #(format "%02x" (bit-and % 0xff)) digest))))

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
  (let [now-time (utils/now)]
    (first (rdbms/insert! database
                          :photos (assoc photo
                                    :created-at now-time
                                    :modified-at now-time)
                          :ex-subtype :UnableToCreatePhoto))))

(def get-photo-versions
  (rdbms/make-finder :photo_versions))

(defn update-photo!
  "Update a photo's metadata, scoped to hostname. Only photo-title is
  settable — the caller's `updates` map is destructured, not passed
  through. Verified exploitable 2026-07-03: the previous version took a
  raw `photo` map (typically `(merge {:id photo-id} body-params)`) and
  used it wholesale as both the id to update AND the fields to set. Since
  malli's `[:map ...]` schemas are open by default (extra keys pass
  through coercion unchanged, not stripped), a request body could include
  its own :id and redirect the update to a *different* photo than the one
  whose existence/hostname was just checked — any site's admin could edit
  any other site's photo metadata this way, bypassing the per-site RBAC
  boundary entirely. Returns the updated row, or nil if not found under
  that hostname."
  [database photo-id hostname {:keys [photo-title]}]
  (first (rdbms/scoped-update! database
                               :photos
                               {:id photo-id :hostname hostname}
                               (cond-> {:modified-at (utils/now)}
                                 photo-title (assoc :photo-title photo-title)))))

(defn create-photo-version!
  [database photo-version]
  (let [now-time (utils/now)]
    (first (rdbms/insert! database
                          :photo-versions (assoc photo-version
                                            :id (utils/uuid)
                                            :created-at now-time
                                            :modified-at now-time)
                          :ex-subtype :UnableToCreatePhotoVersion))))

(def IMAGE-VERSIONS
  ;; category  wd   ht
  [:raw
   :thumbnail                                               ;;[100 100]
   :gallery                                                 ;;[165 165]
   :monitor                                                 ;;[1920 1080]
   :mobile                                                  ;;[1200 630]
   ])

(defn make-image-version
  [_static-content-adapter photo-id hostname extension now-time image-version-name]
  (let [id (utils/uuid)
        image-category (name image-version-name)
        path (format "%s/%s/%s.%s" MEDIA-FOLDER photo-id image-category extension)]
    (-> {:image-category image-category
         :photo-id       photo-id
         :hostname       hostname
         :id             id
         :path           path
         :filename       (format "%s.%s" image-category extension)
         :created-at     now-time
         :modified-at    now-time})))

(defn create-photo-version-2!
  [database photo-versions]
  (span/with-span! {:name "kaleidoscope.api.photo-version.create"}
    ;;(log/infof "Creating photo version for %s" path)
    (let [now (utils/now)]
      (println "Creating photo versions " (count photo-versions))
      (rdbms/insert! database
                     :photo-versions (map (fn [{:keys [id created-at] :as photo-version}]
                                            (cond-> photo-version
                                                    (not id) (assoc :id (utils/uuid))
                                                    (not created-at) (assoc :created-at now :modified-at now)))
                                          photo-versions)
                     :ex-subtype :UnableToCreatePhotoVersion))))

(defn new-image
  [{:keys [static-content-adapter database resize-gate] :as components}
   hostname
   {:keys [filename tempfile extension photo-id] :as file}]
  (let [photo-id (or photo-id (utils/uuid))
        now-time (utils/now)

        ;; Checksum the uploaded bytes and stamp it on the RAW version only — the
        ;; other categories are renditions the resizer produces later (different
        ;; bytes, checksum out of scope here). Nil on the rest keeps the inserted
        ;; rows column-uniform. Reconciliation verifies stored bytes against this.
        content-hash (str "sha256:" (sha256-hex (java.nio.file.Files/readAllBytes (.toPath ^java.io.File tempfile))))

        raw-key (format "%s/%s/raw.%s" MEDIA-FOLDER photo-id extension)]

    ;; Write the raw bytes FIRST: a failed write must throw before any
    ;; photo/version row exists, rather than leaving rows that point at an
    ;; object that was never actually stored.
    (fs/put-file static-content-adapter
                 raw-key
                 (u/->file-input-stream tempfile)
                 (dissoc file :tempfile :file-input-stream))

    (let [photo    (create-photo! database {:id photo-id :hostname hostname})
          versions (map (fn [image-version-name]
                          (assoc (make-image-version static-content-adapter photo-id hostname extension now-time image-version-name)
                                 :content-hash (when (= :raw image-version-name) content-hash)))
                        IMAGE-VERSIONS)
          results  (create-photo-version-2! database versions)]
      ;; Only once the rows exist do we hand the raw off for async resizing —
      ;; enqueue-warm! is non-blocking (offer, never wait) and never throws.
      ;; `resize-gate` is nil for any caller that hasn't been wired up yet
      ;; (gate wiring into env/config is a separate task) — degrade to a
      ;; no-op rather than NPE on `(:queue nil)`.
      (when resize-gate
        (resize/enqueue-warm! resize-gate photo-id extension))
      {:photo-id photo-id
       :versions (vec results)})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Photos in albums
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn add-photos-to-album! [database album-id photo-ids]
  (let [now-time (utils/now)
        ;; the link is tenant data; its hostname is the album's (the composite
        ;; FKs then also require every photo to share that tenant).
        hostname (:hostname (first (get-albums database {:id album-id})))
        photos-in-album (vec (for [photo-id (if (seq? photo-ids) photo-ids [photo-ids])]
                               {:id          (utils/uuid)
                                :photo-id    photo-id
                                :album-id    album-id
                                :hostname    hostname
                                :created-at  now-time
                                :modified-at now-time}))]
    (vec (rdbms/insert! database
                        :photos_in_albums photos-in-album
                        :ex-subtype :UnableToAddPhotoToAlbum))))

(defn remove-content-album-link!
  "Delete one or more album-content links, scoped to album-id. Fixes the
  TODO already left in http_api/album.clj ('This would allow a user to
  delete contents from an album that is different from the path
  specified'). Not a cross-tenant authorization boundary under the
  current design — albums have no per-site scoping at all (unlike
  photos), so any site-admin already has blanket access to every album
  regardless — but a real correctness gap: the album-id named in the URL
  should constrain which content-links a request can actually affect,
  rather than silently accepting any content-id regardless of which
  album it belongs to."
  [database album-id album-content-ids]
  (doseq [id (if (coll? album-content-ids) album-content-ids [album-content-ids])]
    (rdbms/scoped-delete! database :photos_in_albums {:id id :album-id album-id}
                          :ex-subtype :UnableToDeletePhotoFromAlbum)))

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
