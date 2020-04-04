(ns andrewslai.clj.persistence.config
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.mock :as mock]
            [andrewslai.clj.persistence.postgres :as postgres]))

(def ^:dynamic *live-db?* (@env/env :live-db?))

(defn db-conn []
  (if *live-db?*
    (postgres/->Database postgres/pg-db)
    (mock/make-db)))

(comment
  (require '[andrewslai.clj.persistence.core :as db])
  (db/save-article! (db-conn))

  (binding [*live-db?* false]
    (count (db/get-all-articles (db-conn))))
  )
