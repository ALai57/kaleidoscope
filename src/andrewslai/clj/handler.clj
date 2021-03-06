(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.utils :as util]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :refer [wrap-authentication wrap-authorization]]
            [compojure.api.middleware :as mw]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [org.httpkit.server :as httpkit]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.util.http-response :refer [content-type resource-response]]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global settings (Yuck!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(log/merge-config!
 {:appenders {:spit (appenders/spit-appender {:fname "log.txt"})}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compojure Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defroutes index-routes
  (GET "/" []
    (-> (resource-response "index.html" {:root "public"})
        (content-type "text/html"))))

(def app-routes
  (api index-routes
       ping-routes
       articles-routes
       portfolio-routes
       admin-routes
       swagger-ui-routes))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Middleware
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn wrap-request-identifier [handler]
  (fn [request]
    (handler (assoc request :request-id (str (java.util.UUID/randomUUID))))))

(defn log-request! [handler]
  (fn [{:keys [request-method uri request-id] :as request}]
    (handler (do (log/info "Request received: " {:method request-method
                                                 :uri uri
                                                 :request-id request-id})
                 request))))

(defn wrap-logging [handler]
  (fn [{:keys [request-method uri request-id] :as request}]
    (log/with-config (get-in request [::mw/components :logging])
      (handler request))))

(defn wrap-middleware
  "Wraps a set of Compojure routes with middleware and adds
  components via the wrap-components middleware"
  [routes components]
  (-> routes
      (wrap-authentication (:auth components))
      (wrap-authorization (:auth components))
      (wrap-resource "public")
      wrap-content-type
      log-request!
      wrap-logging
      (mw/wrap-components components)
      wrap-request-identifier))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn -main
  "Start a server and run the application"
  [& {:keys [port]}]
  (let [port (or port
                 (some-> (System/getenv "ANDREWSLAI_PORT")
                         int)
                 5000)]
    (println "Hello! Starting andrewslai on port" port)
    (httpkit/run-server
     (wrap-middleware app-routes
                      {:database (pg/->Database (util/pg-conn))
                       :logging  (merge log/*config* {:level :info})
                       :auth     (keycloak/oauth-backend
                                  (keycloak/make-keycloak
                                   {:realm             "test"
                                    :ssl-required      "external"
                                    :auth-server-url   "http://172.17.0.1:8080/auth/"
                                    :client-id         "test-login-java"
                                    :client-secret     "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"
                                    :confidential-port 0}))
                       :session  {:cookie-attrs {:max-age 3600 :secure true}
                                  :store        (mem/memory-store (atom {}))}})
     {:port port})))
