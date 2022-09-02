(ns andrewslai.clj.http-api.middleware
  (:gen-class)
  (:require [buddy.auth.accessrules :as ar]
            [buddy.auth.middleware :as ba]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.json :refer [wrap-json-response]]
            [ring.util.http-response :refer [unauthorized]]
            [taoensso.timbre :as log]))

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

(def standard-stack
  "Stack is applied from top down"
  (apply comp [wrap-request-identifier
               wrap-redirect-to-index
               wrap-content-type
               wrap-json-response]))

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
