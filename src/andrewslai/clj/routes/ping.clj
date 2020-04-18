(ns andrewslai.clj.routes.ping
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :refer [trim]]
            [compojure.api.sweet :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(defn get-sha []
  (->> "HEAD"
       (sh "git" "rev-parse" "--short")
       :out
       trim))

(defn ping-handler []
  (ok {:service-status "ok"
       :sha (get-sha)}))

(defroutes ping-routes
  (GET "/ping" []
    (ping-handler)))
