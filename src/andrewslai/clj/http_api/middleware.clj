(ns andrewslai.clj.http-api.middleware
  (:require [andrewslai.clj.http-api.cache-control :as cc]
            [buddy.auth.accessrules :as ar]
            [buddy.auth.middleware :as ba]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.http-response :refer [unauthorized]]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom middlewares
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn log-request! [handler]
  (fn [request]
    (handler (do (log/infof "Request received\n %s"
                            (-> request
                                (select-keys [:request-method
                                              :uri
                                              :body
                                              :params
                                              :multipart-params
                                              :request-id])
                                clojure.pprint/pprint
                                with-out-str))
                 request))))

(defn debug-log-request!
  "A logging tool that is useful for debugging"
  [msg handler]
  (fn [request]
    (handler (do (log/infof "Debug log: %s" msg)
                 (log/infof "Ring Request: %s" request)
                 request))))

(defn wrap-request-identifier
  [handler]
  (fn [request]
    (handler (assoc request :request-id (str (java.util.UUID/randomUUID))))))

(defn wrap-redirect-to-index
  [handler]
  (fn [{:keys [request-method] :as request}]
    (handler (if (= :get request-method)
               (update request :uri #(if (= "/" %) "/index.html" %))
               request))))

(defn- make-cache-control-logger
  [request-id]
  (fn [response]
    (log/infof "Generating Cache control headers for request-id %s\n" request-id)
    response))

(defn wrap-cache-control
  "Wraps responses with a cache-control header"
  [handler]
  (fn [{:keys [request-id uri] :as request}]
    (let [log! (make-cache-control-logger request-id)]
      (->> (handler request)
           (log!)
           (cc/cache-control uri)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configured middleware stacks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def standard-stack
  "Stack is applied from top down"
  (apply comp [wrap-request-identifier
               wrap-redirect-to-index
               wrap-content-type
               wrap-json-response
               wrap-multipart-params
               wrap-params
               log-request!]))

(defn auth-stack
  "Stack is applied from top down"
  [{:keys [auth access-rules] :as components}]
  (apply comp [#(ba/wrap-authorization % auth)
               ;;#(debug-log-request! "1" %)
               #(ba/wrap-authentication % auth)
               ;;#(debug-log-request! "2" %)
               #(ar/wrap-access-rules % {:rules          access-rules
                                         :reject-handler (fn [& args]
                                                           (unauthorized))})]))

(defn classpath-static-content-stack
  "Returns middleware that intercepts requests and serves files from the
  ClassLoader's Classpath."
  [root-path options]
  (apply comp [wrap-cache-control
               #(wrap-resource % root-path options)]))

(defn file-static-content-stack
  "Returns middleware that intercepts requests and serves files relative to
  the root path."
  [root-path options]
  (apply comp [wrap-cache-control
               #(wrap-file % root-path options)]))
