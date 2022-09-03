(ns andrewslai.clj.persistence.rdbms.embedded-postgres-impl
  (:require [andrewslai.clj.persistence.rdbms.migrations :as migrations]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [migratus.core :as migratus])
  (:import
   io.zonky.test.db.postgres.embedded.EmbeddedPostgres))

(defn ->db-spec
  [embedded-db]
  {:classname   "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname     (format "//localhost:%s/postgres" (.getPort embedded-db))
   :user        "postgres"})

(defn start-db!
  []
  (->db-spec (.start (EmbeddedPostgres/builder))))

(def fresh-db!
  (partial rdbms/fresh-db! start-db!))

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
