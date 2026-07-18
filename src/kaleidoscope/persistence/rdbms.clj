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
            [kaleidoscope.persistence.tenant :as tenant]
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

(defmulti handle-map
  (fn [m ^PreparedStatement s i]
    (class s)))

(defmethod handle-map :default
  [m ^PreparedStatement s i]
  ;; H2 doesn't accept PGobject; pass JSON as a plain string instead
  (if (str/starts-with? (.getName (class s)) "org.h2.")
    (.setString s i (json/encode m))
    (.setObject s i (->pgobject m))))

(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentMap
  (set-parameter [m ^PreparedStatement s i]
    (handle-map m s i)))

;; if a SQL parameter is a Clojure hash map or vector, it'll be transformed
;; to a PGobject for JSON/JSONB (or a plain JSON string on H2, via handle-map):
(extend-protocol prepare/SettableParameter
  clojure.lang.IPersistentVector
  (set-parameter [v ^PreparedStatement s i]
    (handle-map v s i)))

;; Cheshire can return LazySeq (not IPersistentVector) when decoding JSON arrays
;; from JSONB columns. Catch any ISeq so round-tripped JSONB arrays don't fail.
(extend-protocol prepare/SettableParameter
  clojure.lang.ISeq
  (set-parameter [s ^PreparedStatement stmt i]
    (handle-map (vec s) stmt i)))

;; if a row contains a PGobject then we'll convert them to Clojure data
;; while reading (if column is either "json" or "jsonb" type):
(extend-protocol rs/ReadableColumn
  ;; H2 returns JSON B as a byte array of the UTF-8-encoded JSON text. Decode as
  ;; UTF-8 — a per-byte (char b) cast corrupts (and throws on) multi-byte chars.
  (Class/forName "[B")
  (read-column-by-label [v _]
    (json/decode (String. ^bytes v java.nio.charset.StandardCharsets/UTF_8) true))
  (read-column-by-index [v _2 _3]
    (json/decode (String. ^bytes v java.nio.charset.StandardCharsets/UTF_8) true))

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
  (next/execute! (tenant/unwrap conn) stmt {:return-keys true
                                            :builder-fn  rs/as-unqualified-kebab-maps}))

(defn- wrap-sql-exceptions
  "Runs `thunk`, rethrowing any PSQLException as a :PersistenceException
  carrying `table` and the driver's SQLState. Every query in this namespace
  funnels through a handful of shared call sites, so without this, Bugsnag's
  frame-based grouping lumps together completely unrelated SQL errors (wrong
  table, bad types, timeouts, ...) as a single 'error' just because they
  bubbled up through the same line - see kaleidoscope.clients.bugsnag, which
  reads :table/:sql-state back out to give each a distinct grouping hash."
  [table query-data thunk]
  (try+
   (thunk)
   (catch org.postgresql.util.PSQLException e
     (log/errorf "Caught PSQLException querying `%s`: %s" table e)
     (throw+ {:type      :PersistenceException
              :table     table
              :sql-state (.getSQLState e)
              :message   {:data query-data :reason (.getMessage e)}}))))

(defn find-by-keys
  ([database table query-map]
   ;; Honor a TenantConn: inject :hostname for tenant-scoped tables, then run
   ;; against the raw datasource. Direct callers (recipes, scrape pipeline)
   ;; rely on this since they don't go through make-finder.
   (let [query-map (tenant/scope-query database table query-map)
         database  (tenant/unwrap database)]
     (span/with-span! {:name (format "kaleidoscope.db.find.%s" table)}
       (wrap-sql-exceptions
        table query-map
        #(next.sql/find-by-keys database
                                (csk/->snake_case_keyword table)
                                (cske/transform-keys csk/->snake_case_keyword query-map)
                                {:builder-fn rs/as-unqualified-kebab-maps}))))))

(defn make-finder
  [table]
  (fn getter
    ([database]
     ;; A scoped handle turns "fetch all" into "fetch all for this tenant"
     ;; (for tenant-scoped tables). For a table with no hostname column,
     ;; scope-query is a no-op, so this fetches all rows against the raw ds.
     (if (tenant/scoped? database)
       (getter (tenant/unwrap database) (tenant/scope-query database table {}))
       (span/with-span! {:name (format "kaleidoscope.db.get.%s" table)}
         (wrap-sql-exceptions
          table nil
          #(next/execute! database
                          [(format "SELECT * FROM %s" (csk/->snake_case_string table))]
                          {:builder-fn rs/as-unqualified-kebab-maps})))))
    ([database query-map]
     ;; Inject the tenant's hostname (tenant-scoped tables only), then run the
     ;; query against the raw datasource — the injected :hostname confines it.
     (if (tenant/scoped? database)
       (getter (tenant/unwrap database) (tenant/scope-query database table query-map))
       (span/with-span! {:name (format "kaleidoscope.db.get2.%s" table)}
       (let [[[where-in-key where-in-vals] :as where-ins] (filter (fn [[k v]]
                                                                    (set? v)) query-map)]
         (cond
           (empty? query-map)      (getter database)
           (= 0 (count where-ins)) (find-by-keys database table query-map)
           (= 1 (count where-ins)) (span/with-span! {:name (format "kaleidoscope.db.find.%s" table)}
                                     (wrap-sql-exceptions
                                      table query-map
                                      #(next/execute! database
                                                      (hsql/format {:select :*
                                                                    :from   table
                                                                    :where  [:in where-in-key where-in-vals]})
                                                      {:builder-fn rs/as-unqualified-kebab-maps})))
           :else                   (throw (ex-info "Multiple `WHERE IN` clauses currently not supported" {})))))))))

(defmulti insert-impl!
  (fn [database table m]
    (class database)))

(defmethod insert-impl! :default
  [database table m]
  (next.sql/insert-multi! database table
                          (if (map? m)
                            [m]
                            m)
                          (merge next/snake-kebab-opts
                                 {:suffix     "RETURNING *"
                                  :builder-fn rs/as-unqualified-kebab-maps})))

(defn insert! [database table m & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.insert.%s" table)}
    (try+
     (insert-impl! (tenant/unwrap database) table m)
     (catch org.postgresql.util.PSQLException e
       (log/errorf "Caught Exception while inserting: %s" e)
       (throw+ (merge {:type      :PersistenceException
                       :table     table
                       :sql-state (.getSQLState e)
                       :message   {:data   (select-keys m [:username :email])
                                   :reason (.getMessage e)}}
                      (when ex-subtype
                        {:subtype ex-subtype}))))
     (catch Object e
       (log/errorf "Caught Exception while inserting: %s" e)
       (throw+ (merge {:type    :PersistenceException
                       :table   table
                       :message {:data   (select-keys m [:username :email])
                                 :reason (if (map? e)
                                           e
                                           (.getMessage e))}}
                      (when ex-subtype
                        {:subtype ex-subtype})))))))

(defmulti update-impl!
  (fn [database table {:keys [id] :as m}]
    (class database)))

(defmethod update-impl! :default
  [database table {:keys [id] :as m}]
  (let [result (next.sql/update! database table (dissoc m :id)
                                 {:id id}
                                 (merge next/snake-kebab-opts
                                        {:suffix    "RETURNING *"
                                         :builder-fn rs/as-unqualified-kebab-maps}))]
    [result]))

(defn update! [database table {:keys [id] :as m} & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.update.%s" table)}
    (wrap-sql-exceptions table {:id id} #(update-impl! (tenant/unwrap database) table m))))

(defmulti scoped-update-impl!
  (fn [database table where-map set-map]
    (class database)))

(defmethod scoped-update-impl! :default
  [database table where-map set-map]
  [(next.sql/update! database table set-map where-map
                     (merge next/snake-kebab-opts
                            {:suffix     "RETURNING *"
                             :builder-fn rs/as-unqualified-kebab-maps}))])

(defn scoped-update!
  "Like `update!`, but scopes the WHERE clause to every key in `where-map`
  (e.g. {:id id :user-id user-id}) instead of just :id. Returns the updated
  row wrapped in a vector (mirroring `update!`/`insert!`), or [nil] if no row
  matched every key — the caller cannot distinguish 'not found' from 'not
  owned', which is the point: ownership is enforced by the WHERE clause
  itself, not by a preceding check that can be skipped."
  [database table where-map set-map & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.scoped-update.%s" table)}
    (wrap-sql-exceptions table where-map #(scoped-update-impl! (tenant/unwrap database) table where-map set-map))))

(defn scoped-delete!
  "Like `delete!`, but scopes the WHERE clause to every key in `where-map`
  instead of just :id — e.g. {:id id :user-id user-id}. Returns the raw
  execute! result, whose shape is backend-dependent (Postgres returns the
  deleted row via an automatic RETURNING; H2 returns an empty result
  regardless of whether a row matched) — it is not a reliable success
  signal. Callers that need one should check existence with a scoped read
  before deleting (see `kaleidoscope.persistence.ownership/delete-owned!`)."
  [database table where-map & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.scoped-delete.%s" table)}
    (try+
     (transact! database (-> (hh/delete-from table)
                             (hh/where (into [:and] (map (fn [[k v]] [:= k v])) where-map))
                             hsql/format))
     (catch org.postgresql.util.PSQLException e
       (throw+ (merge {:type      :PersistenceException
                       :table     table
                       :sql-state (.getSQLState e)
                       :message   {:data   where-map
                                   :reason (.getMessage e)}}
                      (when ex-subtype
                        {:subtype ex-subtype})))))))

(defn delete! [database table ids & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.delete.%s" table)}
    (try+
     (transact! database (-> (hh/delete-from table)
                             (hh/where [:in :id (if (coll? ids)
                                                  ids
                                                  [ids])])
                             hsql/format))
     (catch org.postgresql.util.PSQLException e
       (throw+ (merge {:type      :PersistenceException
                       :table     table
                       :sql-state (.getSQLState e)
                       :message   {:data   ids
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
