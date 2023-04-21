(ns kaleidoscope.http-api.admin
  (:require [compojure.api.sweet :refer [context GET]]
            [ring.util.http-response :refer [ok]]
            [taoensso.timbre :as log]))

(def admin-routes
  (context "/admin" []
    :tags ["admin"]
    (GET "/" []
      :swagger {:summary     "An Echo route"
                :description "Can only be reached if user is authenticated."
                :security    [{:andrewslai-pkce ["roles" "profile"]}]
                :produces    #{"application/json"}
                :responses   {200 {:description "Success"}}}
      (do (log/info "User Authorized for /admin route")
          (ok {:message "Got to the admin-route!"})))))
