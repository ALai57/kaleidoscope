(ns andrewslai.clj.persistence.rdbms.embedded-h2-impl
  (:require [andrewslai.clj.persistence.rdbms.embedded-db-utils :as edb-utils]
            [next.jdbc :as jdbc]))

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

  ;; Just checking to make sure we can connect to the DB and perform the
  ;; migrations
  (jdbc/execute! ds ["select * from information_schema.tables"])
  (jdbc/execute! ds ["select * from schema_migrations"])

  (jdbc/execute! ds ["CREATE TABLE testing (id varchar)"])
  (jdbc/execute! ds ["INSERT INTO testing VALUES ('hello')"])
  (jdbc/execute! ds ["select * from testing"])

  )
