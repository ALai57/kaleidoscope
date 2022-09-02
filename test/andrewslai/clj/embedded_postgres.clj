(ns andrewslai.clj.embedded-postgres
  (:require [clojure.java.jdbc :as jdbc]
            [migratus.core :as migratus]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.migrations :as migrations]
            [andrewslai.clj.persistence.embedded-postgres :as embedded-pg])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defmacro with-embedded-postgres
  "Starts a fresh db - will cleanup and rollback all database transactions
  db-spec defines the database connection. "
  [database & body]
  `(let [db-spec# (embedded-pg/fresh-db!)
         ~database (rdbms/->RDBMS db-spec#)]
     (jdbc/with-db-transaction [txn# db-spec#]
       (jdbc/db-set-rollback-only! txn#)
       ~@body)))
