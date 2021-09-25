(ns andrewslai.clj.routes.andrewslai
  (:require [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.middleware :as mw]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.swagger :refer [swagger-ui-routes]]
            [buddy.auth.middleware :as ba]
            [compojure.api.sweet :refer [api]]
            [compojure.route :as route]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [taoensso.timbre :as log]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (clojure.stacktrace/print-stack-trace e)))

(defn andrewslai-app
  [{:keys [auth logging static-content] :as components}]
  (log/with-config logging
    (api {:components (dissoc components :static-content)
          :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
          :middleware [mw/wrap-request-identifier
                       mw/wrap-redirect-to-index
                       wrap-content-type
                       wrap-json-response
                       mw/log-request!
                       (or static-content identity)
                       #(ba/wrap-authorization % auth)
                       #(ba/wrap-authentication % auth)
                       ]}
         ping-routes
         articles-routes
         portfolio-routes
         admin-routes
         swagger-ui-routes
         (route/not-found "No matching route"))))
