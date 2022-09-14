(ns andrewslai.clj.entities.photo
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]))

(defn get-all-photos [database]
  (rdbms/select database {:select [:*]
                          :from   [:photos]}))

(defn create-photo! [database photo]
  (first (rdbms/insert! database
                        :photos     photo
                        :ex-subtype :UnableToCreatePhoto)))

(defn get-photo [database photo-src]
  (rdbms/select-one database {:select [:*]
                              :from   [:photos]
                              :where  [:= :photos/photo-src photo-src]}))
