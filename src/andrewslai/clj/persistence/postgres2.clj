(ns andrewslai.clj.persistence.postgres2
  (:require [andrewslai.clj.persistence.persistence :as p :refer [Persistence]]
            [andrewslai.clj.utils :refer [validate]]
            [cheshire.core :as json]
            [clojure.java.jdbc :as sql]
            [honeysql.core :as hsql]
            [honeysql.helpers :as hh]
            [slingshot.slingshot :refer [throw+ try+]]
            [andrewslai.clj.utils :as util])
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
  (select [this stmt]
    (sql/query conn stmt))
  (transact! [this stmt]
    (sql/execute! conn stmt {:return-keys true})))

(defn select [database m]
  (p/select database (hsql/format m)))

;; TODO: Deal with m being a collection or not
(defn insert! [database table m & {:keys [ex-subtype
                                          input-validation]}]
  (when input-validation
    (validate input-validation m :IllegalArgumentException))
  (try+
   (p/transact! database (-> (hh/insert-into table)
                             (hh/values [m])
                             hsql/format))
   (catch org.postgresql.util.PSQLException e
     (throw+ (merge {:type :PersistenceException
                     :message {:data (select-keys m [:username :email])
                               :reason (.getMessage e)}}
                    (when ex-subtype
                      {:subtype ex-subtype}))))))

(defn update! [database table m username & {:keys [ex-subtype
                                                   input-validation]}]
  (when input-validation
    (validate input-validation m :IllegalArgumentException))
  (try+
   (p/transact! database (-> (hh/update table)
                             (hh/sset m)
                             (hh/where [:= :username username])
                             hsql/format))
   (catch org.postgresql.util.PSQLException e
     (throw+ (merge {:type :PersistenceException
                     :message {:data (select-keys m [:username :email])
                               :reason (.getMessage e)}}
                    (when ex-subtype
                      {:subtype ex-subtype}))))))

(defn delete! [database table user-id & {:keys [ex-subtype]}]
  (try+
   (p/transact! database (-> (hh/delete-from table)
                             (hh/where [:= :id user-id])
                             hsql/format))
   (catch org.postgresql.util.PSQLException e
     (throw+ (merge {:type :PersistenceException
                     :message {:data user-id
                               :reason (.getMessage e)}}
                    (when ex-subtype
                      {:subtype ex-subtype}))))))

(comment
  (require '[andrewslai.clj.utils :as util])
  (require '[honeysql.helpers :as hh])

  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"
     :role_id    2})

  (p/select (->Database (util/pg-conn))
            {:select [:*] :from [:users]})

  (p/transact! (->Database (util/pg-conn))
               (-> (hh/insert-into :users)
                   (hh/values [example-user])))

  (p/transact! (->Database (util/pg-conn))
               (-> (hh/delete-from :users)
                   (hh/where [:= :users/username (:username example-user)])))

  (p/transact! (->Database (util/pg-conn))
               (-> (hh/update :users)
                   (hh/sset {:first_name "FIRSTNAME"})
                   (hh/where [:= :username (:username example-user)])))
  )
