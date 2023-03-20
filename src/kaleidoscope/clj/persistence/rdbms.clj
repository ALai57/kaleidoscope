(ns kaleidoscope.clj.persistence.rdbms
  (:require [camel-snake-kebab.core :as csk]
            [camel-snake-kebab.extras :as cske]
            [cheshire.core :as json]
            [clojure.spec.alpha :as s]
            [honeysql.core :as hsql]
            [honeysql.helpers :as hh]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [next.jdbc.sql :as next.sql]
            [slingshot.slingshot :refer [throw+ try+]]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log])
  (:import
   org.postgresql.util.PGobject))

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

(defn validate [type data ex]
  (if (s/valid? type data)
    true
    (throw+
     (let [reason (s/explain-str type data)]
       {:type ex
        :subtype type
        :message {:data data
                  :reason reason
                  :feedback (or (:feedback data)
                                reason)}}))))

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
     (if (empty? query-map)
       (getter database)
       (find-by-keys database table query-map)))))

(defn insert! [database table m & {:keys [ex-subtype
                                          input-validation]}]
  (when input-validation
    (validate input-validation m :IllegalArgumentException))
  (span/with-span! {:name (format "kaleidoscope.db.insert.%s" table)}
    (try+
     (transact! database (-> (hh/insert-into table)
                             (hh/values (if (map? m)
                                          [m]
                                          m))
                             hsql/format))
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
                                 :reason (.getMessage e)}}
                      (when ex-subtype
                        {:subtype ex-subtype})))))))

(defn update! [database table m where & {:keys [ex-subtype
                                                input-validation]}]
  (when input-validation
    (validate input-validation m :IllegalArgumentException))
  (span/with-span! {:name (format "kaleidoscope.db.update.%s" table)}
    (try+
     (transact! database (-> (hh/update table)
                             (hh/sset m)
                             (hh/where where)
                             hsql/format))
     (catch org.postgresql.util.PSQLException e
       (throw+ (merge {:type    :PersistenceException
                       :message {:data   m
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
