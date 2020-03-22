(ns andrewslai.clj.persistence.config
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.mock :as mock]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Database connection
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def db-port (@env/env :db-port))
(def db-host (@env/env :db-host))
(def db-name (@env/env :db-name))
(def db-user (@env/env :db-user))
(def db-password (@env/env :db-password))
(def live-db? (@env/env :live-db?))

(def pg-db {:dbtype "postgresql"
            :dbname db-name
            :host db-host
            :user db-user
            :password db-password})

;; TODO: use environment variable to switch
(def db (if nil #_live-db?
            nil
            (mock/make-db)))

(comment
  (require '[andrewslai.clj.persistence.core :as core])
  (core/save-article! db)
  )
