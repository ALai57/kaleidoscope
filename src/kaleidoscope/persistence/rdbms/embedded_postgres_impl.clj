(ns kaleidoscope.persistence.rdbms.embedded-postgres-impl
  (:require [kaleidoscope.persistence.rdbms.embedded-db-utils :as edb-utils]
            [next.jdbc :as next]
            [taoensso.timbre :as log])
  (:import io.zonky.test.db.postgres.embedded.EmbeddedPostgres
           java.time.Duration))

;; Disable Zonky logs because they're so verbose
(log/merge-config! {:min-level [["io.zonky*" :error]
                                ["*" :info]]})

(defn ->db-spec
  [embedded-db]
  {:jdbcUrl (format "jdbc:postgresql://localhost:%s/postgres?user=postgres" (.getPort embedded-db))})

(defonce ^{:doc "Every embedded Postgres started via `start-db!` that has not yet
  been closed. Each instance holds one SysV shared-memory interlock segment
  (un-disableable, even with shared_memory_type=mmap). See `close-open-dbs!`."}
  open-instances
  (atom []))

(defn start-db!
  "Start the Embedded Postgres process and set the output Redirector.

  Normally, Embedded Postgres logs via a separate thread -> this causes Log
  redirection through Timbre to fail.

  The startup wait is raised well above Zonky's 10s default: the native
  Postgres binary can take longer to accept connections under test/CI load
  (concurrent DBs, busy CPU, first-run binary extraction), which otherwise
  surfaces as a flaky \"Gave up waiting for server to start\" failure.

  The started instance is registered in `open-instances` so tests can close it
  (via the `with-clean-dbs` fixture) and reclaim its SysV shm segment."
  []
  (let [pg (-> (EmbeddedPostgres/builder)
               (.setPGStartupWait (Duration/ofSeconds 60))
               (.start))]
    (swap! open-instances conj pg)
    (->db-spec pg)))

(defn close-open-dbs!
  "Stop and deregister every embedded Postgres started since the last call.

  Each instance holds a SysV shared-memory interlock segment. macOS defaults to
  `kern.sysv.shmmni=32`, so instances that are never closed accumulate across a
  test run, saturate the system's segment table, and starve the next `initdb`
  (`shmget ... No space left on device`) — surfacing as a flaky error on a
  random embedded-Postgres test each run. Closing after each test keeps the
  concurrent count at ~1."
  []
  (let [[instances _] (swap-vals! open-instances (constantly []))]
    (doseq [pg instances]
      (try
        (.close ^io.zonky.test.db.postgres.embedded.EmbeddedPostgres pg)
        (catch Throwable t
          (log/warn t "Failed to close embedded Postgres instance"))))))

(defn with-clean-dbs
  "clojure.test `:each` fixture: run the test, then close every embedded
  Postgres it started, freeing their SysV shm segments. See `close-open-dbs!`."
  [f]
  (try
    (f)
    (finally
      (close-open-dbs!))))

(def fresh-db!
  (partial edb-utils/fresh-db! start-db!))

(comment

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


  (next/execute! ds ["SELECT * FROM FINAL TABLE (INSERT INTO testing VALUES ('hello') ) "])
  (next/execute! ds ["INSERT INTO testing VALUES ('hello') RETURNING *"])


  (= (class ds) org.postgresql.jdbc.PgConnection)
  )
