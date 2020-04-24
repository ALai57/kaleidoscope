(ns andrewslai.clj.persistence.postgres-test
  (:require [andrewslai.clj.persistence.users :as users]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.postgres :as postgres]
            [migratus.core :as m])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))


;; https://github.com/whostolebenfrog/lein-postgres
;; https://eli.naeher.name/embedded-postgres-in-clojure/
;; https://github.com/zonkyio/embedded-postgres 
(defn find-user-index [username v]
  (keep-indexed (fn [idx user] (when (= username (:username user)) idx))
                v))

(defn find-index [where-clause db]
  (let [k (first (keys where-clause))
        v (where-clause k)]
    (keep-indexed (fn [idx user] (when (= v (user k)) idx))
                  db)))

(extend-type clojure.lang.IAtom
  rdbms/RelationalDatabase
  (delete! [a table where]
    (let [k (first (keys where))
          v (where k)
          updated (remove #(= v (k %)) ((keyword table) @a))]
      (swap! a assoc (keyword table) updated)
      [1]))
  (select [a table where]
    (let [k (first (keys where))
          v (where k)]
      (if (empty? where)
        ((keyword table) (deref a))
        (filter #(= v (k %)) ((keyword table) (deref a))))))
  (update! [a table payload where]
    (let [idx (first (find-index where (:users @a)))]
      (swap! a update-in [:users idx] merge payload)
      [1]))
  (insert! [a table payload]
    (swap! a update (keyword table) conj payload)))


;; Embedded test database
(defn pg-db->migratus-config [db-spec]
  {:migration-dirs "migrations"
   :store :database
   :db db-spec})

(def ^:dynamic test-pg (-> (EmbeddedPostgres/builder)
                           .start))

(comment
  (.close test-pg)
  )
(def ^:dynamic db-spec {:classname "org.postgresql.Driver"
                        :subprotocol "postgresql"
                        :subname (str "//localhost:" (.getPort test-pg) "/postgres")
                        :user "postgres"})

(m/migrate (pg-db->migratus-config db-spec))

(comment
  (users/register-user! test-user-db
                        {:first_name "Andrew"
                         :last_name "Lai"
                         :email "me@andrewslai.com"
                         :username "Andrew"
                         :avatar (byte-array (map (comp byte int) "Hello world!"))}
                        "password")

  @test-user-db
  (users/get-user test-user-db "new-user")
  )

(comment
  (import (io.zonky.test.db.postgres.util LinuxUtils ArchUtils))
  (ArchUtils/normalize "i386")
  #_(.getPgBinary (DefaultPostgresBinaryResolver/INSTANCE)
                  (LinuxUtils/getDistributionName)
                  (ArchUtils/normalize "i386"))

  ;;https://github.com/zonkyio/embedded-postgres/blob/master/src/main/java/io/zonky/test/db/postgres/embedded/DefaultPostgresBinaryResolver.java
  (import (io.zonky.test.db.postgres.embedded EmbeddedPostgres DefaultPostgresBinaryResolver))
  (require '[clojure.java.jdbc :as jdbc])

  ;; Move this 
  (def pg (-> (EmbeddedPostgres/builder)
              .start))

  ;; Move this so it's passed in as the connection
  (def subname (str "//localhost:" (.getPort pg) "/postgres"))
  (def db-spec {:classname "org.postgresql.Driver"
                :subprotocol "postgresql"
                :subname (str "//localhost:" (.getPort pg) "/postgres")
                :user "postgres"})
  (jdbc/with-db-connection [db db-spec]
    (jdbc/query db "select version()"))

  (def fruit-table-ddl
    (jdbc/create-table-ddl :fruit
                           [[:name "varchar(32)"]
                            [:appearance "varchar(32)"]
                            [:cost :int]
                            [:grade :real]]))

  (jdbc/db-do-commands db-spec
                       [fruit-table-ddl
                        "CREATE INDEX name_ix ON fruit ( name );"])

  (jdbc/insert! db-spec :fruit {:name "apple"
                                :appearance "awesome"
                                :cost 12
                                :grade 1.2})

  (jdbc/with-db-connection [db db-spec]
    (jdbc/query db "select * from fruit"))

  )
