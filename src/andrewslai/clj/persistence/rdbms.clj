(ns andrewslai.clj.persistence.rdbms
  (:require [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [honeysql.core :as hsql]
            [honeysql.helpers :as hh]
            [migratus.core :as migratus]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]
            [slingshot.slingshot :refer [throw+ try+]]
            [clojure.spec.alpha :as s]
            [taoensso.timbre :as log])
  (:import
   org.postgresql.util.PGobject))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; One time configuration - stateful
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(extend-protocol sql/IResultSetReadColumn
  org.postgresql.jdbc.PgArray
  (result-set-read-column [pgobj metadata i]
    (vec (.getArray pgobj)))

  PGobject
  (result-set-read-column [pgobj conn metadata]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (json/parse-string value keyword)
        :else value))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Connection helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; API for interacting with a relational database
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn transact!
  [conn stmt]
  (next/execute! conn stmt {:return-keys true
                            :builder-fn  rs/as-unqualified-kebab-maps}))

(defn select-one
  [database m]
  (let [result (next/execute! database
                              (hsql/format m)
                              {:builder-fn rs/as-unqualified-kebab-maps})]
    (case (count result)
      1 (first result)
      0 nil
      result)))

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

(defn insert! [database table m & {:keys [ex-subtype
                                          input-validation]}]
  (when input-validation
    (validate input-validation m :IllegalArgumentException))
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
                      {:subtype ex-subtype}))))))

(defn update! [database table m where & {:keys [ex-subtype
                                                input-validation]}]
  (when input-validation
    (validate input-validation m :IllegalArgumentException))
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
                      {:subtype ex-subtype}))))))

(defn delete! [database table ids & {:keys [ex-subtype]}]
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
                      {:subtype ex-subtype}))))))

(comment
  (require '[andrewslai.clj.init.config :as config])
  (require '[honeysql.helpers :as hh])

  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"})

  (def database
    (config/configure-database (System/getenv)))

  (select database
          {:select [:*] :from [:users]})

  (insert! database
           :users
           (hh/values [example-user]))

  (select database
          {:select [:*] :from [:users]})


  (transact! database
             (-> (hh/insert-into :users)
                 (hh/values [example-user])))

  (transact! database
             (-> (hh/delete-from :users)
                 (hh/where [:= :users/username (:username example-user)])))

  (transact! database
             (-> (hh/update :users)
                 (hh/sset {:first_name "FIRSTNAME"})
                 (hh/where [:= :username (:username example-user)])))
  )
