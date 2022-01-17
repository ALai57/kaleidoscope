(ns andrewslai.clj.embedded-h2
  (:require [andrewslai.clj.persistence.migrations :as migrations]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.embedded-h2 :as embedded-h2]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

;;
;; Database
;;
(defmacro with-embedded-h2
  "Starts a fresh, in-memory instance of H2
  All operations occur in a transaction and the database is shutdown at the end"
  [ds & body]
  `(try
     (let [db# (embedded-h2/fresh-db!)]
       (jdbc/with-transaction [~ds db# {:rollback-only true}]
         ~@body))))

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
  (with-embedded-h2 datasource
    (println "HELLO")
    (jdbc/execute! datasource
                   ["select * from schema_migrations"]))
  )
