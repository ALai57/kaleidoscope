(ns kaleidoscope.http-api.ping
  (:require [kaleidoscope.utils.versioning :as v]
            [ring.util.http-response :refer [ok]]))

(def PingResponse
  [:map
   [:version :string]])

(def reitit-ping-routes
  ["/ping"
   {:get {:openapi   {:responses
                      {200 {:content
                            {"application/json"
                             {:examples {"two"   {:summary "2"
                                                  :value   {:total 2}}
                                         "three" {:summary "3"
                                                  :value   {:total 3}}}}}}}}
          :responses {200 {:body PingResponse}}
          :handler   (fn [_request]
                       (ok (v/get-version-details)))}}])
