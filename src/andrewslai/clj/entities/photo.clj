(ns andrewslai.clj.entities.photo
  (:require [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s]))

(defn get-all-photos [database]
  (pg/select database {:select [:*]
                       :from   [:photos]}))

(defn create-photo! [database photo]
  (first (pg/insert! database
                     :photos     photo
                     :ex-subtype :UnableToCreatePhoto)))

(defn get-photo [database photo-src]
  (first (pg/select database {:select [:*]
                              :from   [:photos]
                              :where  [:= :photos/photo-src photo-src]})))
