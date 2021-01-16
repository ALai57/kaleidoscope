(ns andrewslai.clj.embedded-db
  (:require [clojure.java.jdbc :as jdbc]
            [migratus.core :as migratus])
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

(defmacro with-embedded-db
  "Starts a fresh db - will cleanup and rollback all database transactions
  db-spec defines the database connection. "
  [db-spec & body]
  `(let [db#   (.start (EmbeddedPostgres/builder))
         ~db-spec (->db-spec db#)]
     (migratus/migrate (spec->migratus ~db-spec))
     (jdbc/with-db-transaction [txn# ~db-spec]
       (jdbc/db-set-rollback-only! txn#)
       ~@body)))
