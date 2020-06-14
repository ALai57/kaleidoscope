(ns andrewslai.clj.test-utils
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.persistence.articles :as articles]
            [andrewslai.clj.persistence.postgres :as postgres]
            [andrewslai.clj.persistence.postgres-test :as ptest]
            [andrewslai.clj.persistence.projects-portfolio :as portfolio]
            [andrewslai.clj.persistence.users :as users]
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

(defn get-db-spec [db]
  {:classname "org.postgresql.Driver"
   :subprotocol "postgresql"
   :subname (str "//localhost:" (.getPort db) "/postgres")
   :user "postgres"})

(defn app
  ([]
   (h/wrap-middleware h/app-routes
                      (h/configure-components {:db-spec ptest/db-spec
                                               :log-level :error
                                               :secure-session? false
                                               :session-atom (atom {})})))
  ([session-atom]
   (h/wrap-middleware h/app-routes
                      (h/configure-components {:db-spec ptest/db-spec
                                               :log-level :error
                                               :secure-session? false
                                               :session-atom session-atom}))))

(defn get-request
  ([route]
   (get-request route (app)))
  ([route app]
   (->> route
        (mock/request :get)
        app)))
