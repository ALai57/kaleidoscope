(ns andrewslai.clj.embedded-postgres
  (:require [clojure.java.jdbc :as jdbc]
            [migratus.core :as migratus]
            [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.migrations :as migrations])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defn ->db-spec
  [embedded-db]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (str "//localhost:" (.getPort embedded-db) "/postgres")
   :user "postgres"})

(defn new-db!
  []
  (.start (EmbeddedPostgres/builder)))

(defmacro with-embedded-postgres
  "Starts a fresh db - will cleanup and rollback all database transactions
  db-spec defines the database connection. "
  [database & body]
  `(let [db#   (.start (EmbeddedPostgres/builder))
         db-spec# (rdbms/get-datasource (->db-spec db#))
         ~database (pg/->NextDatabase db-spec#)]
     (migratus/migrate (migrations/->migratus-config db-spec#))
     (jdbc/with-db-transaction [txn# db-spec#]
       (jdbc/db-set-rollback-only! txn#)
       ~@body)))
