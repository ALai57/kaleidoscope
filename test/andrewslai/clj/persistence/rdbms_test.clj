(ns andrewslai.clj.persistence.rdbms-test
  (:require [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [clojure.test :refer [is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [honeysql.core :as hsql]
            [honeysql.helpers :as hh]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(def gen-human-name
  (gen/let [ascii (gen/vector (gen/choose \a \z) 2 25)]
    (clojure.string/join (map char ascii))))

(def gen-user
  (gen/hash-map :id gen/uuid
                :first-name gen-human-name
                :last-name gen-human-name
                :username gen-human-name
                :email gen/string-alpha-numeric
                :avatar (gen/return nil)))

(defn add-user [user]
  (-> (hh/insert-into :users)
      (hh/values [user])
      hsql/format))

(defn del-user [username]
  (-> (hh/delete-from :users)
      (hh/where [:= :users/username username])
      hsql/format))

(def all-users
  {:select [:*] :from [:users]})

;; Not all drivers return all keys
;; https://clojurians.slack.com/archives/C1Q164V29/p1601667389036400
(defspec insert-get-delete-test 10
  (prop/for-all [user gen-user]
    (let [database (embedded-h2/fresh-db!)]
      (and (is (empty?   (rdbms/select database all-users)))
           (is (match?   (rdbms/transact! database (add-user user)) [user]))
           (is (= [user] (rdbms/select database all-users)))
           (is (= []     (rdbms/transact! database (del-user (:username user)))))
           (is (empty?   (rdbms/select database all-users)))))))
