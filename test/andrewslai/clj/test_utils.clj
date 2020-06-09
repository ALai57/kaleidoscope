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
(defmacro defdbtest [test-name db-spec & body]
  `(deftest ~test-name
     (jdbc/with-db-transaction [db-spec# ~db-spec]
       (jdbc/db-set-rollback-only! db-spec#)
       (binding [~db-spec db-spec#] 
         ~@body))))

(defn components [session-atom]
  {:user (-> ptest/db-spec
             postgres/->Postgres
             users/->UserDatabase)
   :session {:store (mem/memory-store session-atom)}
   :portfolio (-> ptest/db-spec
                  postgres/->Postgres
                  portfolio/->ProjectPortfolioDatabase)
   :db (-> ptest/db-spec
           postgres/->Postgres
           articles/->ArticleDatabase)})

(defn test-app
  ([]
   (h/wrap-middleware h/bare-app (components (atom {}))))
  ([session-atom]
   (h/wrap-middleware h/bare-app (components session-atom))))

(defn get-request
  ([route]
   (get-request route (test-app)))
  ([route app]
   (->> route
        (mock/request :get)
        app)))
