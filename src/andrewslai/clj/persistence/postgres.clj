(ns andrewslai.clj.persistence.postgres
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [clojure.walk :refer [keywordize-keys]]
            [honeysql.core :as hsql])
  (:import (org.postgresql.util PGobject)))

(extend-protocol sql/IResultSetReadColumn
  PGobject
  (result-set-read-column [pgobj conn metadata]
    (let [type  (.getType pgobj)
          value (.getValue pgobj)]
      (case type
        "json" (keywordize-keys
                 (json/parse-string value))
        :else value))))

(extend-protocol sql/IResultSetReadColumn
  org.postgresql.jdbc.PgArray
  (result-set-read-column [pgobj metadata i]
    (vec (.getArray pgobj))))

(def db-port (@env/env :db-port))
(def db-host (@env/env :db-host))
(def db-name (@env/env :db-name))
(def db-user (@env/env :db-user))
(def db-password (@env/env :db-password))

(def pg-db {:dbtype "postgresql"
            :dbname db-name
            :host db-host
            :user db-user
            :password db-password})


(defn -insert! [this table payload]
  (sql/insert! (:conn this) table payload))

(defn -update! [this table payload where]
  (let [k (first (keys where))
        v (where k)]
    (sql/update! (:conn this)
                 table
                 payload
                 [(format "%s = ?" (name k)) v])
    '(1)))

(defn -delete! [this table where]
  (let [k (first (keys where))
        v (where k)]
    (sql/query (:conn this)
               table
               [(format "DELETE FROM %s WHERE %s = ?" table k) v])))

;; FIXME: Move to HoneySQL? HugSQL?
;; FIXME: This implementation is not right
(defn ->sql-query
  ([table]
   [(format "SELECT * FROM %s" table)])
  ([table where-clause]
   (if (empty? where-clause)
     (->sql-query table)
     (let [k (first (keys where-clause))
           v (get where-clause k)]
       [(format "SELECT * FROM %s WHERE %s = ?" table (name k)) v]))))

(defn -select [this table payload]
  (sql/query (:conn this) (->sql-query table payload)))

(defn hselect [this sql-map]
  (sql/query (:conn this) (hsql/format sql-map)))

(comment
  (hsql/format {:select [:*]
                :from [:articles]}))

(defrecord Postgres [conn]
  rdbms/RelationalDatabase
  (hselect [this sql-map]
    (hselect this sql-map))
  (select [this table where]
    (-select this table where))
  (delete! [this table where]
    (-delete! this table where))
  (update! [this table payload where]
    (-update! this table payload where))
  (insert! [this table payload]
    (-insert! this table payload)))

(comment
  (-select {:conn pg-db} )
  (sql/query pg-db ["SELECT * FROM users"])
  )
