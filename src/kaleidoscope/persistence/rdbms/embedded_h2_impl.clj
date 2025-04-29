(ns kaleidoscope.persistence.rdbms.embedded-h2-impl
  (:require [kaleidoscope.persistence.rdbms.embedded-db-utils :as edb-utils]
            [clojure.string :as str]
            [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [next.jdbc :as next]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.sql :as next.sql]
            [next.jdbc.result-set :as rs]
            [cheshire.core :as json]
            [kaleidoscope.persistence.rdbms :as rdbms])
  (:import (java.sql PreparedStatement Types)))


(defmethod rdbms/handle-map org.h2.jdbc.JdbcPreparedStatement
  [m ^PreparedStatement s i]
  (.setObject s i (json/encode m) Types/OTHER))

(defmethod rdbms/insert-impl! org.h2.jdbc.JdbcConnection
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

(defn using
  [m]
  (let [qns     (str/join ", " (repeat (count m) "?"))
        sources (str/join ", " (map csk/->snake_case_string (keys m)))]
    (format "(VALUES (%s)) AS source(%s)" qns sources)))

(defn eq-stmts
  [field]
  (format "target.%s = source.%s" field field))

(defn not-matched
  [m]
  (let [field-names  (map csk/->snake_case_string (keys m))
        source-names (map (partial str "source.") field-names)]
    (format "(%s) VALUES (%s)"
            (str/join ", " field-names)
            (str/join ", " source-names))))

(defn matched
  [m]
  (let [m   (map csk/->snake_case_string (keys (dissoc m :id)))
        eq-stmts (map eq-stmts m)]
    (str/join "," eq-stmts)))


(defn hsql-upsert
  "Insert into the DB and return the inserted row.
  This is because H2 does not support the `DO UPDATE SET` statement"
  [table m]
  (let [merge-stmt            (format "MERGE INTO %s AS target" (csk/->snake_case_string table))
        using-stmt            (format "USING %s" (using m))
        on-stmt               (format "ON %s" "source.id = target.id")
        when-matched-stmt     (format "WHEN MATCHED THEN UPDATE SET %s" (matched m))
        when-not-matched-stmt (format "WHEN NOT MATCHED THEN INSERT %s" (not-matched m))
        returning-*-stmt      (format "SELECT * FROM FINAL TABLE (%s)"
                                      (str/join " " [merge-stmt
                                                     using-stmt
                                                     on-stmt
                                                     when-matched-stmt
                                                     when-not-matched-stmt]))]
    (concat [returning-*-stmt]
            (mapv (fn [v]
                    (cond
                      (nil? v) nil
                      (map? v) (json/encode v)
                      :else    (str v)))
                  (vals m)))))

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
