(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.projects-portfolio :as portfolio]
            [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]
            [ring.middleware.session.memory :as mem]
            [ring.mock.request :as mock]))

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
  {:db-spec db-spec
   :log-level :error
   :secure-session? false
   :session-atom (atom {})})

(defn get-request
  ([route]
   (->> ptest/db-spec
        test-app-component-config
        (h/configure-app h/app-routes)
        (get-request route)))
  ([route app]
   (->> route
        (mock/request :get)
        app)))
