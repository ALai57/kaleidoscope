(ns andrewslai.clj.test-utils
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.test :refer [deftest is testing]]))

;; Deal with the dynamic vars better/at all
(defmacro defdbtest [test-name db-spec & body]
  `(deftest ~test-name
     (jdbc/with-db-transaction [db-spec# ~db-spec]
       (jdbc/db-set-rollback-only! db-spec#)
       (binding [~db-spec db-spec#] 
         ~@body))))
