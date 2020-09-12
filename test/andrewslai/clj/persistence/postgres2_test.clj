(ns andrewslai.clj.persistence.postgres2-test
  (:require [andrewslai.clj.persistence :as p]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.postgres2 :as postgres]
            [andrewslai.clj.test-utils :refer [defdbtest]]
            [clojure.test :refer [is]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
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
      (hh/values [user])))

(defn del-user [username]
  (-> (hh/delete-from :users)
      (hh/where [:= :users/username username])))

(defspec insert-get-delete-test
  (let [db        (postgres/->Database ptest/db-spec)
        all-users {:select [:*] :from [:users]}]
    (prop/for-all [user gen-user]
      (and (is (empty? (p/select db all-users)))
           (is (= user (p/transact! db (add-user user))))
           (let [result (p/select db all-users)]
             (is (= 1 (count result)))
             (is (= user (first result))))
           (is (= user (p/transact! db (del-user (:username user)))))
           (is (empty? (p/select db all-users)))))))
