(ns andrewslai.clj.handler
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.routes.admin :refer [admin-routes]]
            [andrewslai.clj.routes.articles :refer [articles-routes]]
            [andrewslai.clj.routes.ping :refer [ping-routes]]
            [andrewslai.clj.routes.portfolio :refer [portfolio-routes]]
            [andrewslai.clj.routes.swagger :refer [swagger-ui-routes]]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.virtual-hosting :as vh]
            [buddy.auth.accessrules :refer [wrap-access-rules]]
            [buddy.auth.middleware :as ba]
            [compojure.api.middleware :as mw]
            [compojure.api.sweet :refer [api]]
            [compojure.route :as route]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.util.http-response :refer [unauthorized]]
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
  (fn [{:keys [request-method uri request-id body headers params multipart-params] :as request}]
    (handler (do (log/with-config (:logging (mw/get-components request))
                   (log/info "Request received: " {:method     request-method
                                                   :uri        uri
                                                   :body       body
                                                   :params     params
                                                   :multipart-params multipart-params
                                                   ;;:headers    headers
                                                   :request-id request-id}))
                 request))))

(defn wrap-request-identifier [handler]
  (fn [request]
    (handler (assoc request :request-id (str (java.util.UUID/randomUUID))))))

(defn wrap-index [handler]
  (fn [{:keys [request-method] :as request}]
    (handler (if (= :get request-method)
               (update request :uri #(if (= "/" %) "/index.html" %))
               request))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Compojure Routes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; (require '[ring.middleware.stacktrace :refer [wrap-stacktrace]])

;; TODO: App won't start properly if all component keys are not specified
;;       Difficult to trace down why this happens, so need better error handling
(defn andrewslai-app
  [{:keys [auth logging static-content] :as components}]
  (log/with-config (:logging components)
    (api {:components (dissoc components :static-content)
          :middleware [wrap-request-identifier
                       log-request!
                       wrap-index
                       wrap-content-type
                       wrap-json-response
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

;; TODO: Verify this?
;; Need to tell AWS what region the S3 bucket is in
;; or else it tries to look up something it can't find
;; using the EC2 instance metadata endpoints
(defn wedding-app
  [{:keys [auth logging storage] :as components}]
  (log/with-config logging
    (api {:components (select-keys components [:storage :logging])
          :middleware [wrap-request-identifier
                       log-request!
                       wrap-index
                       wrap-content-type
                       wrap-json-response
                       wrap-multipart-params
                       wrap-params
                       ;; Use storage in here -> create wrapper at this place
                       (sc/static-content storage)
                       #(ba/wrap-authorization % auth)
                       #(ba/wrap-authentication % auth)
                       #(wrap-access-rules % {:rules wedding/access-rules
                                              :reject-handler (fn [& args]
                                                                (unauthorized))})
                       ]}
         ping-routes
         wedding/upload-routes
         (route/not-found "No matching route"))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn start-app
  [{:keys [port andrewslai wedding] :as configuration}]
  (http/start-server
   (vh/host-based-routing
    {#"caheriaguilar.and.andrewslai.com"
     {:priority 0
      :app      (wedding-app wedding)}
     #".*"
     {:priority 100
      :app      (andrewslai-app andrewslai)}})
   {:port port}))

(comment
  ;;(import [com.amazonaws.auth AWSCredentialsProvider])
  (instance? AWSCredentialsProvider (ContainerCredentialsProvider.))
  )
