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
            [taoensso.timbre :as log]))

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
         :else                   (throw (ex-info "Multiple `WHERE IN` clauses currently not supported")))))))


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

(defn update! [database table m where & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.update.%s" table)}
    (try+
     (let [query           (-> (hh/update table)
                               (hh/set m)
                               (hh/where where))
           formatted-query (if (= (class database) org.h2.jdbc.JdbcConnection)
                             (hsql-insert query)
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
