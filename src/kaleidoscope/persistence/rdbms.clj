(ns kaleidoscope.persistence.rdbms
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [clojure.string :as str]
            [honey.sql :as hsql]
            [honey.sql.helpers :as hh]
            [next.jdbc :as next]
            [next.jdbc.prepare :as prepare]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as next.sql]
            [slingshot.slingshot :refer [throw+ try+]]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]
            [cheshire.core :as json])
  (:import (org.postgresql.util PGobject)
           (java.sql PreparedStatement Types)))


(defn ->pgobject
  "Transforms Clojure data to a PGobject that contains the data as
  JSON. PGObject type defaults to `jsonb` but can be changed via
  metadata key `:pgtype`"
  [x]
  (let [pgtype (or (:pgtype (meta x)) "jsonb")]
    (doto (PGobject.)
      (.setType pgtype)
      (.setValue (json/encode x)))))

(defn <-pgobject
  "Transform PGobject containing `json` or `jsonb` value to Clojure
  data."
  [^org.postgresql.util.PGobject v]
  (let [type  (.getType v)
        value (.getValue v)]
    (if (#{"jsonb" "json"} type)
      (when value
        (with-meta (json/decode value true) {:pgtype type}))
      value)))

;; if a SQL parameter is a Clojure hash map or vector, it'll be transformed
;; to a PGobject for JSON/JSONB:
(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (if (= org.h2.jdbc.JdbcPreparedStatement (class s))
      (.setObject s i (json/encode m) Types/OTHER)
      (.setObject s i (->pgobject m))))

  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (.setObject s i (->pgobject v))))

;; if a row contains a PGobject then we'll convert them to Clojure data
;; while reading (if column is either "json" or "jsonb" type):
(extend-protocol rs/ReadableColumn
  ;; H2 returns JSON B as a byte array
  (Class/forName "[B")
  (read-column-by-label [v _]
    (json/decode (apply str (map char v)) true))
  (read-column-by-index [v _2 _3]
    (json/decode (apply str (map char v)) true))

  org.postgresql.util.PGobject
  (read-column-by-label [^org.postgresql.util.PGobject v _]
    (<-pgobject v))
  (read-column-by-index [^org.postgresql.util.PGobject v _2 _3]
    (<-pgobject v)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API for interacting with a relational database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn transact!
  [conn stmt]
  (next/execute! conn stmt {:return-keys true
                            :builder-fn  rs/as-unqualified-kebab-maps}))

(defn find-by-keys
  ([database table query-map]
   (span/with-span! {:name (format "kaleidoscope.db.find.%s" table)}
     (next.sql/find-by-keys database
                            (csk/->snake_case_keyword table)
                            (cske/transform-keys csk/->snake_case_keyword query-map)
                            {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn make-finder
  [table]
  (fn getter
    ([database]
     (span/with-span! {:name (format "kaleidoscope.db.get.%s" table)}
       (next/execute! database
                      [(format "SELECT * FROM %s" (csk/->snake_case_string table))]
                      {:builder-fn rs/as-unqualified-kebab-maps})))
    ([database query-map]
     (span/with-span! {:name (format "kaleidoscope.db.get2.%s" table)}
       (let [[[where-in-key where-in-vals] :as where-ins] (filter (fn [[k v]]
                                                                    (set? v)) query-map)]
         (cond
           (empty? query-map)      (getter database)
           (= 0 (count where-ins)) (find-by-keys database table query-map)
           (= 1 (count where-ins)) (span/with-span! {:name (format "kaleidoscope.db.find.%s" table)}
                                     (next/execute! database
                                                    (hsql/format {:select :*
                                                                  :from   table
                                                                  :where  [:in where-in-key where-in-vals]})
                                                    {:builder-fn rs/as-unqualified-kebab-maps}))
           :else                   (throw (ex-info "Multiple `WHERE IN` clauses currently not supported" {}))))))))

(defn insert! [database table m & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.insert.%s" table)}
    (try+
     (if (= (class database) org.h2.jdbc.JdbcConnection)
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
                                {:builder-fn rs/as-unqualified-kebab-maps})))
       (next.sql/insert-multi! database table
                               (if (map? m)
                                 [m]
                                 m)
                               (merge next/snake-kebab-opts
                                      {:suffix     "RETURNING *"
                                       :builder-fn rs/as-unqualified-kebab-maps})))
     (catch org.postgresql.util.PSQLException e
       (log/errorf "Caught Exception while inserting: %s" e)
       (throw+ (merge {:type    :PersistenceException
                       :message {:data   (select-keys m [:username :email])
                                 :reason (.getMessage e)}}
                      (when ex-subtype
                        {:subtype ex-subtype}))))
     (catch Object e
       (log/errorf "Caught Exception while inserting: %s" e)
       (throw+ (merge {:type    :PersistenceException
                       :message {:data   (select-keys m [:username :email])
                                 :reason (if (map? e)
                                           e
                                           (.getMessage e))}}
                      (when ex-subtype
                        {:subtype ex-subtype})))))))

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

(defn update! [database table {:keys [id] :as m} & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.update.%s" table)}
    (try+
     (if (= (class database) org.h2.jdbc.JdbcConnection)
       (next/execute! database
                      (hsql-upsert table m)
                      {:return-keys true
                       :builder-fn  rs/as-unqualified-kebab-maps})
       [(next.sql/update! database table (dissoc m :id)
                          {:id id}
                          (merge next/snake-kebab-opts
                                 {:suffix    "RETURNING *"
                                  :builder-fn rs/as-unqualified-kebab-maps}))]))))

(defn delete! [database table ids & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.delete.%s" table)}
    (try+
     (transact! database (-> (hh/delete-from table)
                             (hh/where [:in :id (if (coll? ids)
                                                  ids
                                                  [ids])])
                             hsql/format))
     (catch org.postgresql.util.PSQLException e
       (throw+ (merge {:type    :PersistenceException
                       :message {:data   ids
                                 :reason (.getMessage e)}}
                      (when ex-subtype
                        {:subtype ex-subtype})))))))

#_:clj-kondo/ignore
(comment
  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"})
  )
