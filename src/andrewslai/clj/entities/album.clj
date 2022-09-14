(ns andrewslai.clj.entities.album
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]))

(defn get-all-albums [database]
  (rdbms/select database {:select [:*]
                          :from   [:enhanced_albums]}))

(defn create-album! [database album]
  (first (rdbms/insert! database
                        :albums     album
                        :ex-subtype :UnableToCreateAlbum)))

(defn get-album-by-id [database album-id]
  (rdbms/select-one database {:select [:*]
                              :from   [:enhanced_albums]
                              :where  [:= :albums/id album-id]}))

(defn get-album [database album-name]
  (rdbms/select-one database {:select [:*]
                              :from   [:enhanced_albums]
                              :where  [:= :albums/album-name album-name]}))

(defn update-album! [database album]
  (first (rdbms/update! database
                        :albums     album
                        [:= :id (:id album)]
                        :ex-subtype :UnableToUpdateAlbum)))

;; Album contents
(defn add-photos-to-album! [database photos-in-albums]
  (vec (rdbms/insert! database
                      :photos_in_albums photos-in-albums
                      :ex-subtype :UnableToAddPhotoToAlbum)))

(defn remove-content-from-album! [database album-id album-content-id]
  (rdbms/delete! database
                 :photos_in_albums album-content-id
                 :ex-subtype :UnableToDeletePhotoFromAlbum))

(defn get-album-content [database album-id album-content-id]
  (rdbms/select-one database
                    {:select [:*]
                     :from   [:album_contents]
                     :where  [:and
                              [:= :album_contents/album-id album-id]
                              [:= :album_contents/album-content-id album-content-id]]}))

(defn get-all-contents [database]
  (rdbms/select database
                {:select [:*]
                 :from   [:album_contents]}))

(defn get-album-contents [database album-id]
  (rdbms/select database
                {:select [:*]
                 :from   [:album_contents]
                 :where  [:= :album_contents/album-id album-id]}))
