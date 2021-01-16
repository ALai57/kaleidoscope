(ns andrewslai.clj.persistence.postgres2-test
  (:require [andrewslai.clj.persistence.persistence :as persist]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.embedded-db :refer [with-embedded-db]]
            [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [honeysql.core :as hsql]
            [honeysql.helpers :as hh]))

(def gen-human-name
  (gen/let [ascii (gen/vector (gen/choose \a \z) 2 25)]
    (clojure.string/join (map char ascii))))

(def gen-user
  (gen/hash-map :id gen/uuid
                :first_name gen-human-name
                :last_name gen-human-name
                :username gen-human-name
                :email gen/string-alpha-numeric
                :avatar (gen/return nil)
                :role_id (gen/choose 1 2)))

(defn add-user [user]
  (-> (hh/insert-into :users)
      (hh/values [user])
      hsql/format))

(defn del-user [username]
  (-> (hh/delete-from :users)
      (hh/where [:= :users/username username])
      hsql/format))

(def all-users
  (hsql/format {:select [:*] :from [:users]}))

(defspec insert-get-delete-test
  (with-embedded-db db-spec
    (let [database (pg/->Database db-spec)]
      (prop/for-all [user gen-user]
        (and (is (empty?   (persist/select database all-users)))
             (is (= user   (persist/transact! database (add-user user))))
             (is (= [user] (persist/select database all-users)))
             (is (= user   (persist/transact! database (del-user (:username user)))))
             (is (empty?   (persist/select database all-users))))))))
