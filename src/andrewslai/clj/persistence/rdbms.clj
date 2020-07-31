(ns andrewslai.clj.persistence.rdbms
  (:require [clojure.java.jdbc :as sql]))

(defprotocol RelationalDatabase
  (insert! [this table payload])
  (select [this sql-map])
  (update! [this table payload where])
  (delete! [this table payload]))

