(ns andrewslai.clj.api.albums
  (:require [andrewslai.clj.entities.album :as album]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s])
  (:import [java.util UUID]))

(defn now []
  (java.time.LocalDateTime/now))

(def get-all-albums album/get-all-albums)
(def get-album-by-id album/get-album-by-id)
(def get-album album/get-album)

(defn create-album!
  [database album]
  (album/create-album! database (assoc album :id (UUID/randomUUID))))

(def update-album! album/update-album!)

;; Album contents
(defn add-photos-to-album! [database album-id photo-ids]
  (let [now-time (now)]
    (album/add-photos-to-album! database
                                (vec (for [photo-id (if (seq? photo-ids) photo-ids [photo-ids])]
                                       {:id          (java.util.UUID/randomUUID)
                                        :photo-id    photo-id
                                        :album-id    album-id
                                        :created-at  now-time
                                        :modified-at now-time})))))

(def remove-content-from-album! album/remove-content-from-album!)
(def get-album-content album/get-album-content)
(def get-all-contents album/get-all-contents)
(def get-album-contents album/get-album-contents)
