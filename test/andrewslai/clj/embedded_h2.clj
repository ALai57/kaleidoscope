(ns andrewslai.clj.embedded-h2
  (:require [next.jdbc :as jdbc]
            [andrewslai.clj.persistence.migrations :as migrations]
            [migratus.core :as migratus]
            [andrewslai.clj.persistence.rdbms :as rdbms]))

;;
;; Creating the database
;;
(defn fresh-db
  ([]
   (fresh-db (str (java.util.UUID/randomUUID))))
  ([dbname]
   {:jdbcUrl (format "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" dbname)}))

;;
;; Database
;;
(defmacro with-embedded-h2
  "Starts a fresh, in-memory instance of H2
  All operations occur in a transaction and the database is shutdown at the end"
  [connection & body]
  `(try
     (let [db#         (fresh-db)
           ds#         (rdbms/get-datasource db#)
           ~connection (rdbms/fresh-connection ds#)]
       (migratus/migrate (migrations/->migratus-config (rdbms/fresh-connection ds#)))
       (println "Finished migrations")
       ~@body)))

(comment
  ;; Some examples of how to use this namespace
  (require '[andrewslai.clj.persistence.rdbms :as rdbms])

  (def x
    (fresh-db))

  (def ds
    (rdbms/get-datasource x))

  (def conn
    (rdbms/fresh-connection ds))

  ;; Initialize and perform migrations in the `/resources/migrations` folder
  (migratus/migrate (migrations/->migratus-config (rdbms/fresh-connection ds)))

  ;; Just checking to make sure we can connect to the DB and perform the
  ;; migrations
  (jdbc/execute! conn ["select * from information_schema.tables"])
  (jdbc/execute! conn ["select * from schema_migrations"])

  (jdbc/execute! conn ["CREATE TABLE testing (id varchar)"])
  (jdbc/execute! conn ["INSERT INTO testing VALUES ('hello')"])
  (jdbc/execute! conn ["select * from testing"])

  ;; Testing out the `with-embedded-db` macro
  (with-embedded-h2 connection
    (println "HELLO")
    (jdbc/execute! connection
                   ["select * from schema_migrations"]))
  )
