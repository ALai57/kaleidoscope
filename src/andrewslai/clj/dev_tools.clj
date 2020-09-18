(ns andrewslai.clj.dev-tools
  (:require [andrewslai.clj.env :as env]
            [andrewslai.clj.persistence.postgres2 :as pg]))

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

(defn postgres-db
  ([]
   (postgres-db (pg-conn)))
  ([conn]
   (pg/->Database conn)))
