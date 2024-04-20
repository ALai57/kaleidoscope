(ns kaleidoscope.persistence.rdbms
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [honey.sql :as hsql]
            [honey.sql.helpers :as hh]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as next.sql]
            [slingshot.slingshot :refer [throw+ try+]]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]
            [cheshire.core :as json]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API for interacting with a relational database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn transact!
  [conn stmt]
  (next/execute! conn stmt {:return-keys true
                            :builder-fn  rs/as-unqualified-kebab-maps}))

(defn select
  [database m]
  (next/execute! database
                 (hsql/format m)
                 {:builder-fn rs/as-unqualified-kebab-maps}))

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
           :else                   (throw (ex-info "Multiple `WHERE IN` clauses currently not supported"))))))))


(defn hsql-insert
  "Insert into the DB and return the inserted row.
  This is because H2 does not support the `RETURNING` statement"
  [query]
  (let [forms     (->> [:select :* :from :final :table query]
                       hsql/format-expr-list
                       (apply concat)
                       vec)
        statement (take 6 forms)
        params    (drop 6 forms)]
    (concat [(clojure.string/join " " statement)] params)))

(defn insert! [database table m & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.insert.%s" table)}
    (try+
     (let [query           (-> (hh/insert-into table)
                               (hh/values (if (map? m)
                                            [m]
                                            m)))
           formatted-query (if (= (class database) org.h2.jdbc.JdbcConnection)
                             (hsql-insert query)
                             (-> query
                                 (hh/returning :*)
                                 hsql/format))]
       (transact! database formatted-query))
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
  [{:keys [values] :as query}]
  (let [fields  (first values)
        qns     (clojure.string/join ", " (repeat (count fields) "?"))
        sources (clojure.string/join ", " (map csk/->snake_case_string (keys fields)))]
    (format "(VALUES (%s)) AS source(%s)" qns sources)))

(defn eq-stmts
  [field]
  (format "target.%s = source.%s" field field))

(defn on
  [{:keys [values] :as query}]
  (let [fields   (map csk/->snake_case_string (keys (first values)))
        eq-stmts (map eq-stmts fields)
        sources  (clojure.string/join " AND " eq-stmts)]
    (clojure.string/join " AND " eq-stmts)))

(defn not-matched
  [{:keys [values] :as query}]
  (let [fields       (first values)
        field-names  (map csk/->snake_case_string (keys fields))
        source-names (map (partial str "source.") field-names)]
    (format "(%s) VALUES (%s)"
            (clojure.string/join ", " field-names)
            (clojure.string/join ", " source-names))))

(defn matched
  [{:keys [values] :as query}]
  (let [fields   (map csk/->snake_case_string (keys (dissoc (first values) :id)))
        eq-stmts (map eq-stmts fields)]
    (clojure.string/join "," eq-stmts)))

(defn hsql-upsert
  "Insert into the DB and return the inserted row.
  This is because H2 does not support the `DO UPDATE SET` statement"
  [{:keys [values] :as query}]
  (let [merge-stmt            (format "MERGE INTO %s AS target" (csk/->snake_case_string (first (:insert-into query))))
        using-stmt            (format "USING %s" (using query))
        on-stmt               (format "ON %s" "source.id = target.id"#_(on query))
        when-matched-stmt     (format "WHEN MATCHED THEN UPDATE SET %s" (matched query))
        when-not-matched-stmt (format "WHEN NOT MATCHED THEN INSERT %s" (not-matched query))
        returning-*-stmt      (format "SELECT * FROM FINAL TABLE (%s)"
                                      (clojure.string/join " " [merge-stmt
                                                                using-stmt
                                                                on-stmt
                                                                when-matched-stmt
                                                                when-not-matched-stmt]))]
    (concat [returning-*-stmt]
            (mapv (fn [v]
                    (if (nil? v)
                      nil
                      (str v)))
                  (vals (first values))))))

(defn update! [database table m & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.update.%s" table)}
    (try+
     (let [query           (-> (hh/insert-into table)
                               (hh/values [m])
                               (hh/on-conflict :id)
                               (hh/do-update-set (dissoc m :id)))
           formatted-query (if (= (class database) org.h2.jdbc.JdbcConnection)
                             (hsql-upsert query)
                             (-> query
                                 (hh/returning :*)
                                 hsql/format))]
       (transact! database formatted-query)))))

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

(comment
  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"})
  )
