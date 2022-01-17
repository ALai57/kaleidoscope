(ns andrewslai.clj.persistence.embedded-postgres
  (:require [andrewslai.clj.persistence.migrations :as migrations]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [migratus.core :as migratus])
  (:import
   io.zonky.test.db.postgres.embedded.EmbeddedPostgres))

(defn ->db-spec
  [embedded-db]
  {:classname   "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname     (format "//localhost:%s/postgres" (.getPort embedded-db))
   :user        "postgres"})

(defn start-db!
  []
  (.start (EmbeddedPostgres/builder)))

(defn fresh-db!
  []
  (let [datasource (-> (start-db!)
                       (->db-spec)
                       (rdbms/get-datasource))]
    (-> datasource
        (migrations/->migratus-config)
        (migratus/migrate))
    datasource))
