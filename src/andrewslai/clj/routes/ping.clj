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

(defroutes ping-routes
  (GET "/ping" []
    (ok {:service-status "ok"
         :sha (get-sha)})))
