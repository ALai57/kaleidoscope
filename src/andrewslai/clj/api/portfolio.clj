(ns andrewslai.clj.api.portfolio
  (:require [andrewslai.clj.entities.portfolio :as portfolio]))

(defn get-portfolio [database]
  (portfolio/get-portfolio database))
