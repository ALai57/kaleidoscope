(ns kaleidoscope.persistence.rdbms.migrations
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.rdbms.live-pg :as live-pg]
            [migratus.core :as m]
            [next.jdbc :as next]))

(def MIGRATUS-COMMANDS
  {"migrate"  m/migrate
   "pending"  m/pending-list
   "rollback" m/rollback
   "reset"    m/reset
   "up"       m/up
   "down"     m/down
   "init"     m/init
   "create"   m/create})

(defn -main
  "Entry point for running migrations.
  Migratus commands take database `config` as their first argument and
  additional args after that."
  [& [v & args]]
  (let [op  (get MIGRATUS-COMMANDS v m/migrate)
        pg  (live-pg/pg-conn (System/getenv))]
    (with-open [connection (next/get-connection pg)]
      (apply op (concat [{:migration-dir "migrations"
                          :store          :database
                          :db             {:connection connection}}]
                        args)))))

(comment
  ;; MIGHT HAVE TO REQUIRE SOME MODULES... THIS WAS FAILING UNTIL I EVALUATED
  ;; THE BUFFER WITH THIS MODULE

  (m/create {:migration-dir "migrations"}
            "add-title-to-articles")
  (-main "init")
  (-main "migrate")
  (-main "up")
  (-main "reset")

  (require '[migratus.database :as mig-db])

  (def connection
    (next/fresh-connection (live-pg/pg-conn (System/getenv))))

  (def migratus-config
    (->migratus-config connection))

  (def mystore
    (mig-db/->Database connection migratus-config))

  (mig-db/table-exists? mystore "schema_migrations")

  (import '[java.sql SQLException])

  ;; Try a transaction
  (sql/with-db-transaction [conn mystore]
    (println "*********************\n\n")
    (try
      (println "Connection for the transaction" conn)
      (sql/db-set-rollback-only! conn)
      (sql/query conn [(str "SELECT 1 FROM " "schema_migrations")])
      true
      (catch SQLException _
        false)))

  )
