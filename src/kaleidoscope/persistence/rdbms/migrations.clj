(ns kaleidoscope.persistence.rdbms.migrations
  (:require [kaleidoscope.persistence.rdbms.live-pg :as live-pg]
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

  )
