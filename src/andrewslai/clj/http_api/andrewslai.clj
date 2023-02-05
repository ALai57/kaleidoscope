(ns andrewslai.clj.http-api.andrewslai
  (:require [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.admin :refer [admin-routes]]
            [andrewslai.clj.http-api.articles :refer [articles-routes branches-routes compositions-routes]]
            [andrewslai.clj.http-api.ping :refer [ping-routes]]
            [andrewslai.clj.http-api.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.http-api.photo :refer [photo-routes]]
            [andrewslai.clj.http-api.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.stacktrace :as stacktrace]
            [compojure.api.sweet :refer [api context GET]]
            [taoensso.timbre :as log]))

(def public-access
  (constantly true))

(def ANDREWSLAI-ACCESS-CONTROL-LIST
  [{:pattern #"^/admin.*"        :handler (partial auth/require-role "andrewslai")}
   {:pattern #"^/articles.*"     :handler (partial auth/require-role "andrewslai")}
   {:pattern #"^/branches.*"     :handler (partial auth/require-role "andrewslai")}
   {:pattern #"^/compositions.*" :handler public-access}
   {:pattern #"^/$"              :handler public-access}
   {:pattern #"^/index.html$"    :handler public-access}
   {:pattern #"^/ping"           :handler public-access}
   #_{:pattern #"^/.*" :handler (constantly false)}])

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
       :body    (fs/get static-content-adapter "index.html")})
    (GET "/index.html" []
      :components [static-content-adapter]
      {:status  200
       :headers {"Content-Type" "text/html"}
       :body    (fs/get static-content-adapter "index.html")})))

(def default-handler
  (GET "*" {:keys [uri] :as request}
    :components [static-content-adapter]
    (if-let [response (fs/get static-content-adapter uri)]
      {:status 200
       :body   response}
      {:status 404})))

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
       photo-routes
       default-handler))


(comment
  ((andrewslai-app {:auth           identity
                    :static-content nil})
   {:request-method :get
    :uri    "hi"}))
