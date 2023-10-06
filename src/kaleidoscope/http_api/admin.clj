(ns kaleidoscope.http-api.admin
  (:require [compojure.api.sweet :refer [context GET]]
            [ring.util.http-response :refer [ok]]
            [taoensso.timbre :as log]))

(def AdminResponse
  [:map
   [:message :string]])

(def reitit-admin-routes
  ["/admin" {:tags     ["admin"]
             :security [{:andrewslai-pkce ["roles" "profile"]}]
             ;; For testing only - this is a mechanism to always get results from a particular
             ;; host URL.
             ;;
             ;;:host      "andrewslai.localhost"
             :get {:summary     "An echo route"
                   :description "Can only be reached if user is authenticated."
                   :responses   {200 {:description "Authorized for route"
                                      :content     {"application/json"
                                                    {:schema   AdminResponse
                                                     :examples {"example-admin-response"
                                                                {:summary "Example successful admin response"
                                                                 :body    AdminResponse
                                                                 :value   {:message "Got to the admin-route!"}}}}}}}

                   :handler (fn [{:keys [components parameters] :as request}]
                              (do (log/info "User authorized for /admin route")
                                  (ok {:message "Got to the admin-route!"})))}}])
