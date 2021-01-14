(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]
            [taoensso.timbre :as log]))

;; Deal with the dynamic vars better/at all
(defmacro defdbtest
  "Defines a test that will cleanup and rollback all database transactions
  db-spec defines the database connection. "
  [test-name db-spec & body]
  `(deftest ~test-name
     (jdbc/with-db-transaction [db-spec# ~db-spec]
       (jdbc/db-set-rollback-only! db-spec#)
       (binding [~db-spec db-spec#] 
         ~@body))))

(defn test-app-component-config [db-spec]
  {:database (pg/->Database db-spec)
   :logging  (merge log/*config* {:level :error})
   :session  {:cookie-attrs {:max-age 3600 :secure false}
              :store        (mem/memory-store (atom {}))}})

(defn get-request
  ([route]
   (->> ptest/db-spec
        test-app-component-config
        (h/wrap-middleware h/app-routes)
        (get-request route)))
  ([route app]
   (->> route
        (mock/request :get)
        app)))
