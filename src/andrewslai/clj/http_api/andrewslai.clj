(ns andrewslai.clj.http-api.andrewslai
  (:require [andrewslai.clj.http-api.admin :refer [admin-routes]]
            [andrewslai.clj.http-api.articles :refer [articles-routes branches-routes compositions-routes]]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.http-api.swagger :refer [swagger-ui-routes]]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [ANY GET api context]]
            [taoensso.timbre :as log]
            [andrewslai.clj.persistence.filesystem :as fs]))

(defn exception-handler
  [e data request]
  (log/errorf "Error: %s, %s"
              (ex-message e)
              (stacktrace/print-stack-trace e)))

(def index-routes
  (context "/" []
    (GET "/" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/get-file static-content-adapter "index.html")})
    (GET "/index.html" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/get-file static-content-adapter "index.html")})))

(def default-handler
  (ANY "*" []
    {:status 404}))

(defn andrewslai-app
  [{:keys [http-mw] :as components}]
  (api {:components components
        :exceptions {:handlers {:compojure.api.exception/default exception-handler}}
        :middleware [http-mw]}
       ping-routes
       index-routes
       articles-routes
       branches-routes
       compositions-routes
       portfolio-routes
       admin-routes
       swagger-ui-routes
       default-handler))


(comment
  ((andrewslai-app {:auth           identity
                    :static-content nil})
   {:request-method :get
    :uri    "hi"}))
