(ns kaleidoscope.http-api.ping
  (:require [kaleidoscope.utils.versioning :as v]
            [ring.util.http-response :refer [ok]]))

(def PingResponse
  [:map
   [:version :string]
   [:revision {:optional true} :string]])

(def reitit-ping-routes
  ["/ping"
   {:get {:responses {200 {:content
                           {"application/json"
                            {:schema   PingResponse
                             :examples {"healthy-ping" {:summary "Healthy ping response"
                                                        :value   {:version "1.1.1.1"}}}}}}}
          :handler   (fn [_request]
                       (ok (v/get-version-details)))}}])
