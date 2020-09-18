(ns andrewslai.clj.persistence.users2
  (:require [andrewslai.clj.auth.crypto :refer [encrypt make-encryption]]
            [andrewslai.clj.utils :refer [validate]]
            [andrewslai.clj.entities.user :as u :refer [User]]
            [andrewslai.clj.persistence :as p :refer [Persistence]]
            [honeysql.helpers :as hh]
            [slingshot.slingshot :refer [throw+ try+]]))

;; CREATE looks similar for all different ways of creating
;; So does delete
;; Can these be combined?
;;
;; If so, should I just provide functions that call a generic "create" "delete", etc?

;; TODO: Deal with m being a collection or not
(defn create! [database table m & {:keys [ex-subtype
                                          validation-spec]}]
  (when validation-spec
    (validate validation-spec m :IllegalArgumentException))
  (try+
   (p/transact! database (-> (hh/insert-into table)
                             (hh/values [m])))
   (catch org.postgresql.util.PSQLException e
     (throw+ (merge {:type :PersistenceException
                     :message {:data (select-keys m [:username :email])
                               :reason (.getMessage e)}}
                    (when ex-subtype
                      {:subtype ex-subtype}))))))

(defrecord UserDB [database]
  User
  (create-user! [this user]
    (create! database
             :users user
             :ex-subtype :UnableToCreateUser
             :validation-spec :andrewslai.user/user))

  (get-users [this]
    (p/select database {:select [:*]
                        :from [:users]}))

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
                          :reason (.getMessage e)}})))))

(comment
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
