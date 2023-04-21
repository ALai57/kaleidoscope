(ns kaleidoscope.persistence.rdbms.live-pg)

(defn pg-conn
  [env]
  {:dbname   (get env "KALEIDOSCOPE_DB_NAME")
   :db-port  (get env "KALEIDOSCOPE_DB_PORT" "5432")
   :host     (get env "KALEIDOSCOPE_DB_HOST")
   :user     (get env "KALEIDOSCOPE_DB_USER")
   :password (get env "KALEIDOSCOPE_DB_PASSWORD")
   :dbtype   "postgresql"})
