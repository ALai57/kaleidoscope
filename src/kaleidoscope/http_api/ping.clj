(ns kaleidoscope.http-api.ping
  (:require [compojure.api.sweet :refer [defroutes GET]]
            [kaleidoscope.utils.versioning :as v]
            [ring.util.http-response :refer [ok]]))

(defroutes ping-routes
  (GET "/ping" []
    (ok (v/get-version-details))))

(def PingResponse
  [:map
   [:version :string]])

(def reitit-ping-routes
  ["/ping" {:get {:responses {200 {:body PingResponse}}
                  :openapi   {:responses
                              {200 {:content
                                    {"application/json"
                                     {:examples {"two"   {:summary "2"
                                                          :value   {:total 2}}
                                                 "three" {:summary "3"
                                                          :value   {:total 3}}}}}}}}
                  :handler   (fn [_request]
                               (ok (v/get-version-details)))}}])
