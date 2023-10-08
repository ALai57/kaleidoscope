(ns kaleidoscope.persistence.rdbms.embedded-h2-impl
  (:require [kaleidoscope.persistence.rdbms.embedded-db-utils :as edb-utils]))

(defn start-db!
  ([]
   (start-db! (str (java.util.UUID/randomUUID))))
  ([dbname]
   {:jdbcUrl (format "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=1" dbname)}))

(def fresh-db!
  (partial edb-utils/fresh-db! start-db!))

(comment

  (def x
    (start-db!))

  (def ds
    (fresh-db!))

  (require '[next.jdbc :as next])

  ;; Just checking to make sure we can connect to the DB and perform the
  ;; migrations
  (next/execute! ds ["select * from information_schema.tables"])
  (next/execute! ds ["select * from schema_migrations"])

  (next/execute! ds ["CREATE TABLE testing (id varchar)"])
  (next/execute! ds ["INSERT INTO testing VALUES ('hello') RETURNING *"])
  (next/execute! ds ["select * from testing"])


  ;; Testing `RETURNING` statement - H2 doesn't support it and uses
  ;; data delta tables instead
  (next/execute! ds ["SELECT * FROM FINAL TABLE (INSERT INTO testing VALUES ('hello') ) "])

  (= (class ds) org.h2.jdbc.JdbcConnection)
  )
