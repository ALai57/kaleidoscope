(ns andrewslai.clj.persistence.users2
  (:require [andrewslai.clj.auth.crypto :refer [encrypt make-encryption]]
            [andrewslai.clj.utils :refer [validate]]
            [andrewslai.clj.entities.user :as u :refer [User]]
            [andrewslai.clj.persistence :as p :refer [Persistence]]
            [honeysql.helpers :as hh]
            [slingshot.slingshot :refer [throw+ try+]]))

(defrecord UserDB [database]
  User
  (create-user! [this user]
    (try+
     (validate :andrewslai.user/user user :IllegalArgumentException)
     (p/transact! database (-> (hh/insert-into :users)
                               (hh/values [user])))
     (catch org.postgresql.util.PSQLException e
       (throw+ {:type :PersistenceException
                :subtype ::UnableToCreateUser
                :message {:data (select-keys user [:username :email])
                          :reason (.getMessage e)
                          :feedback "Try a different username and/or email"}}))))
  (create-login! [this id password]
    (validate :andrewslai.user/password password :IllegalArgumentException)
    (try+
     (let [encrypted-password (encrypt (make-encryption) password)]
       (p/transact! database
                    (-> (hh/insert-into :logins)
                        (hh/values [{:id              id
                                     :hashed_password encrypted-password}]))))
     (catch org.postgresql.util.PSQLException e
       (throw+ {:type :PersistenceException
                :subtype ::UnableToCreateLogin
                :message {:data {:id id}
                          :reason (.getMessage e)}}))))
  (get-users [this]
    (p/select database {:select [:*]
                        :from [:users]})))

(comment
  (require '[andrewslai.clj.env :as env])
  (require '[andrewslai.clj.dev-tools :as tools])

  (def example-user
    {:id         #uuid "f5778c59-e57d-46f0-b5e5-516e5d36481c"
     :first_name "Andrew"
     :last_name  "Lai"
     :username   "alai"
     :avatar     nil
     :email      "andrew@andrew.com"
     :role_id    2})

  (def user-db
    (->UserDB (tools/postgres-db)))

  (u/create-user! user-db
                  example-user)

  (u/get-users user-db)

  )
