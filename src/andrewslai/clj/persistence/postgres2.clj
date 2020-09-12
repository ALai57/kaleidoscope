(ns andrewslai.clj.persistence.postgres2
  (:require [andrewslai.clj.persistence :as p :refer [Persistence]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [honeysql.core :as hsql])
  (:import org.postgresql.util.PGobject))

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

(defrecord Database [conn]
  Persistence
  (select [this m]
    (sql/query conn (hsql/format m)))
  (transact! [this m]
    (sql/execute! conn (hsql/format m) {:return-keys true})))

(comment
  (require '[andrewslai.clj.env :as env])
  (require '[honeysql.helpers :as hh])

  (defn pg-conn []
    (-> @env/env
        (select-keys [:db-port :db-host
                      :db-name :db-user
                      :db-password])
        (clojure.set/rename-keys {:db-name     :dbname
                                  :db-host     :host
                                  :db-user     :user
                                  :db-password :password})
        (assoc :dbtype "postgresql")))

  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"
     :role_id    2})

  (p/select (->Database (pg-conn))
            {:select [:*] :from [:users]})

  (p/transact! (->Database (pg-conn))
               (-> (hh/insert-into :users)
                   (hh/values [example-user])))

  (p/transact! (->Database (pg-conn))
               (-> (hh/delete-from :users)
                   (hh/where [:= :users/username (:username example-user)])))

  )
