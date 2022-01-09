(ns andrewslai.clj.persistence.migrations
  (:require [andrewslai.clj.config :as config]
            [clojure.java.jdbc :as sql]
            [migratus.core :as m]))

(defn pg-db->migratus-config [{:keys [host dbname user password]}]
  {:migration-dirs "migrations"
   :store          :database
   :db             {:classname   "com.postgresql.jdbc.Driver"
                    :subprotocol "postgresql"
                    :subname     (str "//" host "/" dbname)
                    :user        user
                    :password    password}})

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
  (let [op  (get MIGRATUS-COMMANDS v m/migrate)]
    (apply op (concat [(pg-db->migratus-config (config/pg-conn (System/getenv)))]
                      args))))

(comment
  ;; MIGHT HAVE TO REQUIRE SOME MODULES... THIS WAS FAILING UNTIL I EVALUATED
  ;; THE BUFFER WITH THIS MODULE
  (-main "create" "seed-articles-table")
  (-main "init")
  (-main "migrate")
  (-main "up")

  (require '[migratus.database :as mig-db])
  (require '[migratus.protocols :as prot])

  (def migratus-config
    (pg-db->migratus-config (config/pg-conn (System/getenv))))

  (def my-connection
    (-> migratus-config
        (:db)
        (mig-db/connect*)
        (atom)))

  (def mystore
    (mig-db/->Database (:connection @my-connection)
                       migratus-config))

  @my-connection

  mystore

  (import '[java.sql SQLException])

  (sql/with-db-transaction
    [t-con mystore]
    (println "*********************\n\n")
    (try
      (println "t-conn" t-con)
      (sql/db-set-rollback-only! t-con)
      (sql/query t-con [(str "SELECT 1 FROM " "schema_migrations")])
      true
      (catch SQLException _
        false))) 

  (mig-db/table-exists? mystore "schema_migrations")

  (import [org.postgresql.util PSQLException])

  (try (mig-db/table-exists? mystore "schema_migrations")
       #_(throw PSQLException)
       (catch org.postgresql.util.PSQLException _
         (println "Caught PSQL exception")) (catch java.sql.SQLException _
                                              (println "Caught SQL exception")))

  )
