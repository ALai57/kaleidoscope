(ns andrewslai.clj.entities.photo
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.cljc.specs.articles]
            [clojure.spec.alpha :as s]))

(defn get-all-photos [database]
  (rdbms/select database {:select [:*]
                          :from   [:photos]}))

(defn create-photo! [database photo]
  (first (rdbms/insert! database
                        :photos     photo
                        :ex-subtype :UnableToCreatePhoto)))

(defn get-photo [database photo-src]
  (first (rdbms/select database {:select [:*]
                                 :from   [:photos]
                                 :where  [:= :photos/photo-src photo-src]})))
