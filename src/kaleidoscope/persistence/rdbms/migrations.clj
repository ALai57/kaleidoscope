(ns kaleidoscope.persistence.rdbms.migrations
  (:require [kaleidoscope.persistence.rdbms.live-pg :as live-pg]
            [migratus.core :as m]
            [next.jdbc :as next]))

(def MIGRATUS-COMMANDS
  {"migrate"       m/migrate
   "pending"       m/pending-list
   "rollback"      m/rollback
   "reset"         m/reset
   "up"            m/up
   "down"          m/down
   "init"          m/init
   "create"        m/create
   ;; Squash (Migratus >= 1.5). See docs/operations.md for the ordered runbook.
   "squash-list"   m/squashing-list  ; [config from to]        read-only preview
   "squash-create" m/create-squash   ; [config from to name]   rewrites repo files
   "squash-apply"  m/squash-between}) ; [config from to name]   reconciles one DB's schema_migrations

(defn coerce-arg
  "CLI args arrive as strings, but squash `from`/`to` migration ids are compared
  numerically. Parse integer-looking args to Long; leave names (kebab-case) as
  strings. A squash `name` must therefore not be purely numeric."
  [arg]
  (or (parse-long arg) arg))

(defn result-lines
  "Render a migratus command result for stdout. Read-only commands (e.g.
  `squash-list`) *return* their result and only log at debug, so `-main` must
  print it. Collections print one item per line; an empty collection is reported
  explicitly so a list command isn't silently blank; nil (migrate/up/down, which
  log directly) prints nothing."
  [result]
  (cond
    (nil? result)                    []
    (and (coll? result) (empty? result)) ["(no migrations in range)"]
    (coll? result)                   (map str result)
    :else                            [(str result)]))

(defn -main
  "Entry point for running migrations.
  Migratus commands take database `config` as their first argument and
  additional args after that."
  [& [v & args]]
  (let [op  (get MIGRATUS-COMMANDS v m/migrate)
        pg  (live-pg/pg-conn (System/getenv))]
    (with-open [connection (next/get-connection pg)]
      (let [result (apply op (concat [{:migration-dir "migrations"
                                       :store          :database
                                       :db             {:connection connection}}]
                                     (map coerce-arg args)))]
        (run! println (result-lines result))))))

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
