(ns andrewslai.clj.routes.middleware
  (:gen-class)
  (:require [compojure.api.middleware :as mw]
            [taoensso.timbre :as log]))

(defn log-request! [handler]
  (fn [request]
    (handler (do (log/with-config (:logging (mw/get-components request))
                   (log/infof "Request received\n %s" (-> request
                                                          (select-keys [:request-method
                                                                        :uri
                                                                        :body
                                                                        :params
                                                                        :multipart-params
                                                                        :request-id])
                                                          clojure.pprint/pprint
                                                          with-out-str)))
                 request))))

(defn debug-log-request!
  "A logging tool that is useful for debugging"
  [msg handler]
  (fn [request]
    (handler (do (log/with-config (:logging (mw/get-components request))
                   (log/infof "Debug log: %s" msg)
                   (log/infof "Ring Request body: %s" (when (:body request)
                                                        (slurp (:body request)))))
                 request))))

(defn wrap-request-identifier [handler]
  (fn [request]
    (handler (assoc request :request-id (str (java.util.UUID/randomUUID))))))

(defn wrap-redirect-to-index [handler]
  (fn [{:keys [request-method] :as request}]
    (handler (if (= :get request-method)
               (update request :uri #(if (= "/" %) "/index.html" %))
               request))))
