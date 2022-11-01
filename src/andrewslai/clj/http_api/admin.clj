(ns andrewslai.clj.http-api.admin
  (:require [compojure.api.sweet :refer [context defroutes GET]]
            [ring.util.http-response :refer [ok]]
            [taoensso.timbre :as log]))

(defroutes admin-routes
  (context "/admin" []
    :tags ["admin"]
    (GET "/" []
      :swagger {:summary     "An Echo route"
                :description "Can only be reached if user is authenticated."
                :produces    #{"application/json"}
                :responses   {200 {:description "Success"}}}
      (do (log/info "User Authorized for /admin route")
          (ok {:message "Got to the admin-route!"})))))
