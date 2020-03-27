(ns andrewslai.clj.persistence.config
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.mock :as mock]
            [andrewslai.clj.persistence.postgres :as postgres]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.walk :refer [keywordize-keys]])
  (:import (org.postgresql.util PGobject)))


(extend-protocol sql/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj conn metadata]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (keywordize-keys
                 (json/parse-string value))
        :else value))))

(extend-protocol sql/IResultSetReadColumn
  org.postgresql.jdbc.PgArray
  (result-set-read-column [pgobj metadata i]
    (vec (.getArray pgobj))))

(def ^:dynamic *live-db?* (@env/env :live-db?))

(defn db-conn []
  (println "using live db?" *live-db?*)
  (if *live-db?*
    (postgres/make-db)
    (mock/make-db)))

(comment
  (require '[andrewslai.clj.persistence.core :as db])
  (db/save-article! (db-conn))

  (binding [*live-db?* false]
    (count (db/get-all-articles (db-conn))))
  )
