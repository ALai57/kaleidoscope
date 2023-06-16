(ns kaleidoscope.persistence.rdbms.embedded-postgres-impl
  (:require [kaleidoscope.persistence.rdbms.embedded-db-utils :as edb-utils]
            [next.jdbc :as next]
            [taoensso.timbre :as log])
  (:import io.zonky.test.db.postgres.embedded.EmbeddedPostgres))

;; Disable Zonky logs because they're so verbose
(log/merge-config! {:min-level [["io.zonky*" :error]
                                ["*" :info]]})

(defn ->db-spec
  [embedded-db]
  {:jdbcUrl (format "jdbc:postgresql://localhost:%s/postgres?user=postgres" (.getPort embedded-db))})

(defn start-db!
  "Start the Embedded Postgres process and set the output Redirector.

  Normally, Embedded Postgres logs via a separate thread -> this causes Log
  redirection through Timbre to fail."
  []
  (-> (EmbeddedPostgres/builder)
      (.start)
      (->db-spec)))

(def fresh-db!
  (partial edb-utils/fresh-db! start-db!))

(comment
  (require '[next.jdbc :as next])

  (def db
    (log/with-min-level :warn
      (.start (EmbeddedPostgres/builder))))

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
  (next/execute! ds ["select version()"])

  )
