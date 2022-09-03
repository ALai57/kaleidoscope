(ns andrewslai.clj.embedded-postgres
  (:require [andrewslai.clj.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [clojure.java.jdbc :as jdbc])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defmacro with-embedded-postgres
  "Starts a fresh db - will cleanup and rollback all database transactions
  db-spec defines the database connection. "
  [database & body]
  `(let [db-spec# (embedded-pg/fresh-db!)
         ~database db-spec#]
     (jdbc/with-db-transaction [txn# db-spec#]
       (jdbc/db-set-rollback-only! txn#)
       ~@body)))
