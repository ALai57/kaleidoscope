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
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.persistence.s3 :as fs]
            [andrewslai.clj.utils :as util]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [buddy.auth.backends.session :refer [session-backend]]
            [buddy.auth.middleware :as ba]
            [compojure.api.middleware :as mw]
            [compojure.api.routes :as r]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [aleph.http :as http]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-body wrap-json-response]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.middleware.session :refer [wrap-session]]
            [ring.middleware.session.memory :as mem]
            [ring.util.request :as req]
            [ring.util.http-response :refer [content-type resource-response]]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [clojure.spec.alpha :as s])
  (:import [com.amazonaws.auth ContainerCredentialsProvider]))

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compojure Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defroutes index-routes
  (GET "/" []
    (-> (resource-response "index.html" {:root "public"})
        (content-type "text/html"))))

(defn andrewslai-app
  [components]
  (log/with-config (:logging components)
    (api {:components components
          :middleware [wrap-request-identifier
                       log-request!
                       wrap-content-type
                       wrap-json-response
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
         swagger-ui-routes)))

(defn wedding-app
  [components]
  (log/with-config (:logging components)
    (api {:components components
          :middleware [wrap-request-identifier
                       log-request!
                       wrap-content-type
                       wrap-json-response
                       #(wrap-resource % "public")
                       #(ba/wrap-authorization % (:auth components))
                       #(ba/wrap-authentication % (:auth components))
                       #(wrap-access-rules % {:rules wedding/access-rules})
                       ]}
         wedding/index-routes
         wedding/routes)))

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
     (andrewslai-app {:database        (pg/->Database (util/pg-conn))
                      :wedding-storage (fs/make-s3 {:bucket-name "andrewslai-wedding"
                                                    ;; Need to tell AWS what region the S3 bucket is in
                                                    ;; or else it tries to look up something it can't find
                                                    ;; using the EC2 instance metadata endpoints
                                                    :credentials fs/CustomAWSCredentialsProviderChain})
                      :logging         (merge log/*config* {:level :info})
                      :auth            (auth/oauth-backend
                                        (keycloak/make-keycloak
                                         {:realm             (System/getenv "ANDREWSLAI_AUTH_REALM")
                                          :ssl-required      "external"
                                          :auth-server-url   (System/getenv "ANDREWSLAI_AUTH_URL")
                                          :client-id         (System/getenv "ANDREWSLAI_AUTH_CLIENT")
                                          :client-secret     (System/getenv "ANDREWSLAI_AUTH_SECRET")
                                          :confidential-port 0}))})
     {:port port})))

(comment
  ;;(import [com.amazonaws.auth AWSCredentialsProvider])
  (instance? AWSCredentialsProvider (ContainerCredentialsProvider.))
  )
