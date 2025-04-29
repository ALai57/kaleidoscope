(ns kaleidoscope.persistence.rdbms.embedded-h2-impl
  (:require [kaleidoscope.persistence.rdbms.embedded-db-utils :as edb-utils]
            [clojure.string :as str]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.sql :as next.sql]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [kaleidoscope.persistence.rdbms :as rdbms])
  (:import (java.sql PreparedStatement Types)))


(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (if (= org.h2.jdbc.JdbcPreparedStatement (class s))
      (.setObject s i (json/encode m) Types/OTHER)
      (.setObject s i (->pgobject m)))))

(defmethod rdmbs/insert-impl! org.h2.jdbc.JdbcConnection
  [database table m & {:keys [ex-subtype]}]
  (let [new-ids (next.sql/insert-multi! database table
                                        (if (map? m)
                                          [m]
                                          m)
                                        (merge next/snake-kebab-opts
                                               {:return-keys           true
                                                :return-generated-keys true
                                                :builder-fn            rs/as-unqualified-kebab-maps}))]
    (next.sql/query database (concat [(format "SELECT * FROM %s WHERE id in (%s)"
                                              (csk/->snake_case_string table)
                                              (str/join ", " (repeat (count new-ids) "?")))]
                                     (mapv :id new-ids))
                    (merge next/snake-kebab-opts
                           {:builder-fn rs/as-unqualified-kebab-maps}))))

(defmethod rdbms/update-impl! org.h2.jdbc.JdbcConnection
  [database table {:keys [id] :as m}]
  (next/execute! database
                 (hsql-upsert table m)
                 {:return-keys true
                  :builder-fn  rs/as-unqualified-kebab-maps}))

(defn start-db!
  ([]
   (start-db! (str (java.util.UUID/randomUUID))))
  ([dbname]
   {:jdbcUrl (format "jdbc:h2:mem:%s;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=1" dbname)}))

(def fresh-db!
  (partial edb-utils/fresh-db! start-db!))

(comment

  (def x
    (start-db!))

  (def ds
    (fresh-db!))

  (require '[next.jdbc :as next])

  ;; Just checking to make sure we can connect to the DB and perform the
  ;; migrations
  (next/execute! ds ["select * from information_schema.tables"])
  (next/execute! ds ["select * from schema_migrations"])

  (next/execute! ds ["CREATE TABLE testing (id varchar)"])
  (next/execute! ds ["INSERT INTO testing VALUES ('hello') RETURNING *"])
  (next/execute! ds ["select * from testing"])


  ;; Testing `RETURNING` statement - H2 doesn't support it and uses
  ;; data delta tables instead
  (next/execute! ds ["SELECT * FROM FINAL TABLE (INSERT INTO testing VALUES ('hello') ) "])

  (= (class ds) org.h2.jdbc.JdbcConnection)
  )
