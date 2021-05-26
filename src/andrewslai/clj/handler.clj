(ns andrewslai.clj.handler
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.virtual-hosting :as vh]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [buddy.auth.middleware :as ba]
            [compojure.api.middleware :as mw]
            [compojure.api.sweet :refer [api defroutes GET]]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.http-response :refer [content-type resource-response]]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders])
  (:import com.amazonaws.auth.ContainerCredentialsProvider))

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
    (handler (do (log/with-config (:logging (mw/get-components request))
                   (log/info "Request received: " {:method     request-method
                                                   :uri        uri
                                                   :body       body
                                                   ;;:headers    headers
                                                   :request-id request-id}))
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
  [{:keys [auth logging static-content] :as components}]
  (log/with-config (:logging components)
    (api {:components components
          :middleware [wrap-request-identifier
                       log-request!
                       wrap-content-type
                       wrap-json-response
                       (or static-content identity)
                       #(ba/wrap-authorization % auth)
                       #(ba/wrap-authentication % auth)
                       #(wrap-access-rules % {:rules wedding/access-rules})
                       ]}
         index-routes
         ping-routes
         articles-routes
         portfolio-routes
         admin-routes
         swagger-ui-routes)))

;; TODO: Verify this?
;; Need to tell AWS what region the S3 bucket is in
;; or else it tries to look up something it can't find
;; using the EC2 instance metadata endpoints
(defn wedding-app
  [{:keys [auth logging static-content] :as components}]
  (log/with-config logging
    (api {:components (dissoc components :static-content)
          :middleware [wrap-request-identifier
                       log-request!
                       wrap-content-type
                       wrap-json-response
                       (or static-content identity)
                       #(ba/wrap-authorization % auth)
                       #(ba/wrap-authentication % auth)
                       #(wrap-access-rules % {:rules wedding/access-rules})
                       ]}
         wedding/index-routes
         wedding/routes)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-app
  [{:keys [auth database logging port static-content wedding-storage] :as configuration}]
  (http/start-server
   (vh/host-based-routing
    {#"caheriaguilar.and.andrewslai.com"
     {:priority 0
      :app      (wedding-app {:wedding-storage wedding-storage
                              :logging         logging
                              :auth            auth})}
     #".*"
     {:priority 100
      :app      (andrewslai-app {:database       database
                                 :logging        logging
                                 :auth           auth
                                 :static-content static-content})}})
   {:port port}))

(comment
  ;;(import [com.amazonaws.auth AWSCredentialsProvider])
  (instance? AWSCredentialsProvider (ContainerCredentialsProvider.))
  )
