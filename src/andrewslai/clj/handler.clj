(ns andrewslai.clj.handler
  (:gen-class)
  (:require [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.routes.wedding :as wedding :refer [wedding-routes]]
            [andrewslai.clj.persistence.s3 :as fs]
            [andrewslai.clj.utils :as util]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :as ba]
            [compojure.api.middleware :as mw]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [aleph.http :as http]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
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
;; Middleware
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn log-request! [handler]
  (fn [{:keys [request-method uri request-id body headers] :as request}]
    (handler (do (log/info "Request received: " {:method     request-method
                                                 :uri        uri
                                                 :body       body
                                                 ;;:headers    headers
                                                 :request-id request-id})
                 request))))

(defn wrap-request-identifier [handler]
  (fn [request]
    (handler (assoc request :request-id (str (java.util.UUID/randomUUID))))))

(defn wrap-logging [handler]
  (fn [{:keys [request-method uri request-id] :as request}]
    (log/with-config (get-in request [::mw/components :logging])
      (handler request))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compojure Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defroutes index-routes
  (GET "/" []
    (-> (resource-response "index.html" {:root "public"})
        (content-type "text/html"))))

(defn app-routes
  [components]
  (api {:components components
        :middleware [wrap-request-identifier
                     wrap-logging
                     wrap-content-type
                     wrap-json-response
                     log-request!
                     #(wrap-resource % "public")
                     #(ba/wrap-authorization % (:auth components))
                     #(ba/wrap-authentication % (:auth components))
                     #(wrap-access-rules % {:rules wedding/access-rules})
                     ]}
       index-routes
       ping-routes
       articles-routes
       portfolio-routes
       admin-routes
       wedding-routes
       swagger-ui-routes))


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
    (http/start-server
     (app-routes
      {:database        (pg/->Database (util/pg-conn))
       :wedding-storage (fs/make-s3 {:bucket-name "andrewslai-wedding"})
       :logging         (merge log/*config* {:level :info})
       :auth            (auth/oauth-backend
                         (keycloak/make-keycloak
                          {:realm             "test"
                           :ssl-required      "external"
                           :auth-server-url   "http://172.17.0.1:8080/auth/"
                           :client-id         "test-login-java"
                           :client-secret     "18c28e7a-3eb6-4726-b8c7-9c5d02f6bc88"
                           :confidential-port 0}))})
     {:port port})))
