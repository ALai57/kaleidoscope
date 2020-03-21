(ns andrewslai.clj.utils
  (:require [cheshire.core :as json]))

(defn parse-response-body [response]
  (-> response
      :body
      slurp
      (json/parse-string keyword)))
