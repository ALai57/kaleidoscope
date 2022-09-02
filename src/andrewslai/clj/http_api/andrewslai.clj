(ns andrewslai.clj.http-api.andrewslai
  (:require [andrewslai.clj.http-api.admin :refer [admin-routes]]
            [andrewslai.clj.http-api.articles :refer [articles-routes]]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.http-api.swagger :refer [swagger-ui-routes]]
            [buddy.auth.middleware :as ba]
            [buddy.auth.accessrules :as ar]
            [compojure.api.sweet :refer [api ANY]]
            [ring.util.http-response :refer [unauthorized]]
            [compojure.route :as route]
            [clojure.stacktrace :as stacktrace]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [taoensso.timbre :as log]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

(def default-handler
  (ANY "*" []
    {:status 404}))

(defn andrewslai-app
  [{:keys [http-mw] :as components}]
  (api {:components components
        :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
        :middleware [http-mw]}
       ping-routes
       articles-routes
       portfolio-routes
       admin-routes
       swagger-ui-routes
       default-handler))


(comment
  ((andrewslai-app {:auth           identity
                    :static-content nil})
   {:request-method :get
    :uri    "hi"}))
