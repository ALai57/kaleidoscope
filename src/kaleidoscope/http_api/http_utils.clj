(ns kaleidoscope.http-api.http-utils
  (:require [clojure.string :as string]))

(defn remove-port
  [hostname]
  (first (string/split hostname #":")))

(defn get-host
  [request]
  (-> request
      (get-in [:headers "host"])
      (remove-port)))
