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
            [ring.util.response :as resp]
            [ring.util.http-response :refer [unauthorized]]
            [taoensso.timbre :as log]))

(def ^:dynamic *request-id*
  "Unbound")

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
    (let [request-id (str (java.util.UUID/randomUUID))]
      (binding [*request-id* request-id]
        (handler (assoc request :request-id request-id))))))

(defn- cache-control-log!
  [response]
  (log/infof "Generating Cache control headers")
  response)

(defn wrap-cache-control
  "Wraps responses with a cache-control header"
  [handler]
  (fn [{:keys [request-id uri] :as request}]
    (->> (handler request)
         (cache-control-log!)
         (cc/cache-control uri))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configured middleware stacks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def standard-stack
  "Stack is applied from top down"
  (apply comp [wrap-request-identifier
               wrap-content-type
               wrap-json-response
               wrap-multipart-params
               wrap-params
               log-request!]))

(defn auth-stack
  "Stack is applied from top down"
  [authentication-backend access-rules]
  (apply comp [#(ba/wrap-authorization % authentication-backend)
               ;;#(debug-log-request! "1" %)
               #(ba/wrap-authentication % authentication-backend)
               ;;#(debug-log-request! "2" %)
               #(ar/wrap-access-rules % {:rules          access-rules
                                         :reject-handler (fn [& args]
                                                           (-> "Not authorized"
                                                               (unauthorized)
                                                               (resp/content-type "application/text")))})]))
