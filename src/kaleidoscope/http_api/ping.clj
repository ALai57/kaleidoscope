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

(def ping-routes-2
  ["/ping" {:get {:responses  {200 {:body PingResponse}}
                  :handler    (fn [_request]
                                (ok (v/get-version-details)))}}])

(comment
  (require '[reitit.coercion.malli :as rcm])
  (require '[reitit.ring.coercion :as rrc])
  (require '[reitit.ring :as ring])
  (require '[reitit.swagger :as swagger])
  (require '[reitit.swagger-ui :as swagger-ui])

  (def mock-app
    (ring/ring-handler
     (ring/router [ping-routes-2
                   ["" {:no-doc true}
                    ["/swagger.json" {:get (swagger/create-swagger-handler)}]
                    ["/api-docs/*" {:get (swagger-ui/create-swagger-ui-handler)}]]]
                  {:data {:coercion   rcm/coercion
                          :middleware [rrc/coerce-exceptions-middleware
                                       rrc/coerce-request-middleware
                                       rrc/coerce-response-middleware]}})))

  (mock-app {:request-method :get
             :uri            "/ping"}))
