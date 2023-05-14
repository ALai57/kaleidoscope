(ns kaleidoscope.persistence.rdbms.embedded-postgres-impl
  (:require [kaleidoscope.persistence.rdbms.embedded-db-utils :as edb-utils]
            [next.jdbc.connection :as connection]
            [next.jdbc :as next])
  (:import io.zonky.test.db.postgres.embedded.EmbeddedPostgres)
  (:import (com.zaxxer.hikari HikariDataSource)))

(defn ->db-spec
  [embedded-db]
  {:jdbcUrl (format "jdbc:postgresql://localhost:%s/postgres?user=postgres" (.getPort embedded-db))})

(defn start-db!
  []
  (->db-spec (.start (EmbeddedPostgres/builder))))

(def fresh-db!
  (partial edb-utils/fresh-db! start-db!))

(comment

  (require '[next.jdbc :as next])

  (def db
    (.start (EmbeddedPostgres/builder)))

  (.close db)

  (def ds
    (fresh-db!))

  ;; Just checking to make sure we can connect to the DB and perform the
  ;; migrations
  (next/execute! ds ["select * from information_schema.tables"])
  (next/execute! ds ["select * from schema_migrations"])

  (next/execute! ds ["CREATE TABLE testing (id varchar)"])
  (next/execute! ds ["INSERT INTO testing VALUES ('hello')"])
  (next/execute! ds ["select * from testing"])

  )
