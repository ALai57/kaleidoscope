(ns andrewslai.clj.persistence.embedded-h2
  (:require [andrewslai.clj.persistence.migrations :as migrations]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [migratus.core :as migratus]
            [next.jdbc :as jdbc]))

(defn start-db!
  ([]
   (start-db! (str (java.util.UUID/randomUUID))))
  ([dbname]
   {:jdbcUrl (format "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE" dbname)}))

(defn fresh-db!
  []
  (let [datasource (-> (start-db!)
                       (rdbms/get-datasource))
        ;; For some reason, need to create a new connection from this datasource
        ;; before migrating
        conn       (rdbms/fresh-connection datasource)]
    (-> (rdbms/fresh-connection datasource)
        (migrations/->migratus-config)
        (migratus/migrate))
    conn))

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
