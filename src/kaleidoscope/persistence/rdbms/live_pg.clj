(ns kaleidoscope.persistence.rdbms.live-pg)

(defn pg-conn
  [env]
  {:dbname   (get env "KALEIDOSCOPE_DB_NAME")
   :db-port  (get env "KALEIDOSCOPE_DB_PORT" "5432")
   :host     (get env "KALEIDOSCOPE_DB_HOST")
   :user     (get env "KALEIDOSCOPE_DB_USER")
   :password (get env "KALEIDOSCOPE_DB_PASSWORD")
   :dbtype   "postgresql"
   ;; Mirror the runtime pool (init.env/env->pg-conn): managed Postgres (Neon)
   ;; requires TLS, so the migration connection must request it too. Defaults to
   ;; "require" and is overridable for local plaintext via KALEIDOSCOPE_DB_SSL_MODE.
   :sslmode  (get env "KALEIDOSCOPE_DB_SSL_MODE" "require")})
