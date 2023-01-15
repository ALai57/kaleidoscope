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
            [taoensso.timbre :as log])
  (:import [lambdaisland.deep_diff2.diff_impl Mismatch Deletion Insertion]))

;; https://gist.github.com/hsartoris-bard/856d79d3a13f6cafaaa6e5079c76cd97
(def mismatch? (partial instance? Mismatch))
(def deletion? (partial instance? Deletion))
(def insertion? (partial instance? Insertion))

(def diff? (some-fn mismatch? deletion? insertion?))

(def non-empty-collection? (every-pred coll? not-empty))

(defn remove-unchanged
  [x]
  (cond
    (diff? x)      x
    (map-entry? x) (let [[k v] x]
                     (cond
                       (diff? k)                 x
                       (diff? v)                 x
                       (non-empty-collection? v) (when-let [result (remove-unchanged v)]
                                                   [k result])
                       ))
    (coll? x) (when-let [result (->> x
                                     (map remove-unchanged)
                                     (filter not-empty)
                                     (seq))]
                (into (empty x) result))))


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
    (handler (do (log/debugf "Debug log: %s" msg)
                 (log/debugf "Ring Request: %s" (dissoc request
                                                        :compojure.api.request/swagger
                                                        :compojure.api.request/muuntaja
                                                        :compojure.api.request/lookup
                                                        :compojure.api.request/coercion
                                                        :compojure.api.request/paths
                                                        :compojure.api.middleware/components
                                                        :muuntaja/request
                                                        :protocol
                                                        :remote-addr))
                 request))))

(defn wrap-request-identifier
  [handler]
  (fn [request]
    (let [request-id (str (java.util.UUID/randomUUID))]
      (binding [*request-id* request-id]
        (handler (let [modified-request (assoc request :request-id request-id)]
                   (ddiff/pretty-print (remove-unchanged (ddiff/diff request modified-request)))
                   (log/debugf "Adding request-id %s to ring request" request-id)
                   modified-request))))))

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
