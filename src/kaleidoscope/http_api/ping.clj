(ns kaleidoscope.http-api.ping
  (:require [compojure.api.sweet :refer [defroutes GET]]
            [kaleidoscope.utils.versioning :as v]
            [ring.util.http-response :refer [ok]]))

(defroutes ping-routes
  (GET "/ping" []
    (ok (v/get-version-details))))
