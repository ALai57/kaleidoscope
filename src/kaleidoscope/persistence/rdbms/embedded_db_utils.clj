(ns kaleidoscope.persistence.rdbms.embedded-db-utils
  (:require [migratus.core :as migratus]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

(defn fresh-db!
  "Used to create and migrate a fresh database"
  [start-fn]
  (log/with-min-level :error
    (let [datasource (-> (start-fn)
                         (next/get-datasource))
          ;; For some reason, need to create a new connection from this datasource
          ;; before migrating. I think it's because Migratus closes the connection.
          conn       (next/get-connection datasource)]
      (migratus/migrate {:migration-dir "migrations"
                         :store         :database
                         :db            {:connection (next/get-connection datasource)}})
      (migratus/migrate {:migration-dir "db-seed"
                         :store         :database
                         :db            {:connection (next/get-connection datasource)}})
      conn)))
