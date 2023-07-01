(ns kaleidoscope.http-api.middleware
  (:require [buddy.auth.accessrules :as ar]
            [buddy.auth.middleware :as ba]
            [clojure.pprint :as pprint]
            [clojure.string :as string]
            [kaleidoscope.clients.session-tracker :as st]
            [lambdaisland.deep-diff2 :as ddiff]
            [muuntaja.core :as m]
            [reitit.coercion.malli :as rcm]
            [reitit.openapi :as openapi]
            [reitit.ring.coercion :as rrc]
            [reitit.ring.middleware.muuntaja :as muuntaja]
            [reitit.ring.middleware.parameters :as parameters]
            [reitit.swagger :as swagger]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.json :as json-mw :refer [wrap-json-response]]
            [ring.middleware.multipart-params :as mp :refer [wrap-multipart-params]]
            [ring.middleware.params :as params :refer [wrap-params]]
            [ring.util.http-response :refer [unauthorized]]
            [ring.util.response :as resp]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log])
  (:import
   (lambdaisland.deep_diff2.diff_impl Deletion Insertion Mismatch)))

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
                                                   [k result])))
    (coll? x) (when-let [result (->> x
                                     (map remove-unchanged)
                                     (filter not-empty)
                                     (seq))]
                (into (empty x) result))))

(defn string-diff
  [x y]
  (with-out-str (ddiff/pretty-print (remove-unchanged (ddiff/diff x y))
                                    (ddiff/printer {:print-color false}))))

(comment
  (string-diff {:a :b}
               {:c :d}))


(def ^:dynamic *request-id*
  "Unbound")

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Custom middlewares
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn log-request! [handler]
  (fn [request]
    (span/with-span! {:name "kaleidoscope.middleware.log-request"}
      (handler (do (log/infof "Request received\n %s"
                              (-> request
                                  (select-keys [:request-method
                                                :uri
                                                ;;:body
                                                ;;:params
                                                ;;:multipart-params
                                                ;;:request-id
                                                ])
                                  pprint/pprint
                                  with-out-str))
                   request)))))

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

(defn log-transformation-diffs
  [{:keys [name handler]}]
  (fn [x]
    (let [modified-x (handler x)]
      (log/debugf "Difference introduced by %s: %s"
                  name
                  (string-diff x modified-x))
      modified-x)))

(comment
  (require '[ring.mock.request :as mock])
  ((log-transformation-diffs {:name    :Hello
                              :handler add-request-identifier})
   (mock/request :get "/endpoint")))

(defn add-request-identifier
  [request]
  (let [request-id       (str (java.util.UUID/randomUUID))
        modified-request (assoc request :request-id request-id)]
    modified-request))

(defn wrap-request-identifier
  [handler]
  (fn [request]
    (let [request-id (str (java.util.UUID/randomUUID))]
      (binding [*request-id* request-id]
        (handler (let [modified-request (assoc request :request-id request-id)]
                   ;;(ddiff/pretty-print (remove-unchanged (ddiff/diff request modified-request)))
                   (log/debugf "Adding request-id %s to ring request" request-id)
                   modified-request))))))

(defn wrap-trace
  [handler]
  (fn [{:keys [uri request-method] :as request}]
    (span/with-span! {:name (format "kaleidoscope%s.%s"
                                    (string/replace uri "/" ".")
                                    request-method)}
      (handler request))))

(defn session-tracking-stack
  [session-tracker]
  (fn wrap-session-tracking [handler]
    (fn [request]
      (handler (do (when session-tracker
                     (st/start! session-tracker))
                   request)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configured middleware stacks
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def standard-stack
  "Stack is applied from top down"
  (apply comp [wrap-request-identifier
               wrap-trace
               wrap-gzip
               wrap-content-type
               wrap-json-response
               wrap-multipart-params
               wrap-params
               log-request!]))

(defn auth-stack
  "Stack is applied from top down"
  [authentication-backend access-rules]
  (apply comp [#(ba/wrap-authentication % authentication-backend)
               #(ar/wrap-access-rules % {:rules          access-rules
                                         :reject-handler (fn [& args]
                                                           (-> "Not authorized"
                                                               (unauthorized)
                                                               (resp/content-type "application/text")))})]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Reitit configuration
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def reitit-configuration
  "Router data affecting all routes"
  {:data {:coercion   rcm/coercion
          :muuntaja   m/instance
          :middleware [parameters/parameters-middleware
                       openapi/openapi-feature
                       swagger/swagger-feature
                       ;;rrc/coerce-exceptions-middleware
                       rrc/coerce-request-middleware
                       muuntaja/format-response-middleware
                       rrc/coerce-response-middleware]}})
