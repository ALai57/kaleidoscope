(ns andrewslai.clj.persistence.migrations
  (:require [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [migratus.core :as m]))

(def MIGRATUS-COMMANDS
  {"migrate"  m/migrate
   "pending"  m/pending-list
   "rollback" m/rollback
   "reset"    m/reset
   "up"       m/up
   "down"     m/down
   "init"     m/init
   "create"   m/create})

(defn ->migratus-config
  [db]
  {:migration-dirs "migrations"
   :store          :database
   :db             db})

(defn -main
  "Entry point for running migrations.
  Migratus commands take database `config` as their first argument and
  additional args after that."
  [& [v & args]]
  (let [op  (get MIGRATUS-COMMANDS v m/migrate)
        pg  (rdbms/pg-conn (System/getenv))]
    (with-open [connection (rdbms/fresh-connection pg)]
      (apply op (concat [(->migratus-config {:connection connection})]
                        args)))))

(comment
  ;; MIGHT HAVE TO REQUIRE SOME MODULES... THIS WAS FAILING UNTIL I EVALUATED
  ;; THE BUFFER WITH THIS MODULE

  (m/create {:migration-dirs "migrations"}
            "add-image-metadata-table")
  (-main "init")
  (-main "migrate")
  (-main "up")

  (require '[migratus.database :as mig-db])

  (def connection
    (rdbms/fresh-connection (rdbms/pg-conn (System/getenv))))

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
