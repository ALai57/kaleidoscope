(ns andrewslai.clj.persistence.rdbms
  (:require [next.jdbc :as jdbc]))

(defn get-datasource
  [db]
  (jdbc/get-datasource db))

(defn fresh-connection
  [ds]
  (jdbc/get-connection ds))
