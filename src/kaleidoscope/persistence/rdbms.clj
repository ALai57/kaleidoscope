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
     (insert-impl! database table m)
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

(defmulti update-impl!
  (fn [database table {:keys [id] :as m}]
    (class database)))

(defmethod update-impl! :default
  [database table {:keys [id] :as m}]
  [(next.sql/update! database table (dissoc m :id)
                     {:id id}
                     (merge next/snake-kebab-opts
                            {:suffix    "RETURNING *"
                             :builder-fn rs/as-unqualified-kebab-maps}))])

(defn update! [database table {:keys [id] :as m} & {:keys [ex-subtype]}]
  (span/with-span! {:name (format "kaleidoscope.db.update.%s" table)}
    (try+
     (update-impl! database table))))

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
