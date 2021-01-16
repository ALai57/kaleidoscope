(ns andrewslai.clj.embedded-postgres
  (:require [clojure.java.jdbc :as jdbc]
            [migratus.core :as migratus]
            [andrewslai.clj.persistence.postgres2 :as pg])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defn ->db-spec
  [embedded-db]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (str "//localhost:" (.getPort embedded-db) "/postgres")
   :user "postgres"})

(defn spec->migratus [db-spec]
  {:migration-dirs "migrations"
   :store :database
   :db db-spec})

(defmacro with-embedded-postgres
  "Starts a fresh db - will cleanup and rollback all database transactions
  db-spec defines the database connection. "
  [database & body]
  `(let [db#   (.start (EmbeddedPostgres/builder))
         db-spec# (->db-spec db#)
         ~database (pg/->Database db-spec#)]
     (migratus/migrate (spec->migratus db-spec#))
     (jdbc/with-db-transaction [txn# db-spec#]
       (jdbc/db-set-rollback-only! txn#)
       ~@body)))
