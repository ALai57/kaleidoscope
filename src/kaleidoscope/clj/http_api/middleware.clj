(ns kaleidoscope.clj.http-api.middleware
  (:require [kaleidoscope.clj.http-api.cache-control :as cc]
            [buddy.auth.accessrules :as ar]
            [buddy.auth.middleware :as ba]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.gzip :refer [wrap-gzip]]
            [ring.middleware.json :as json-mw :refer [wrap-json-response]]
            [ring.middleware.multipart-params :as mp :refer [wrap-multipart-params]]
            [ring.middleware.params :as params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.response :as resp]
            [ring.util.http-response :refer [unauthorized]]
            [taoensso.timbre :as log]
            [lambdaisland.deep-diff2 :as ddiff])
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

(defn print-diff
  [x y]
  (ddiff/pretty-print (remove-unchanged (ddiff/diff x y))))

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
;; NEW -- Trying to convert middleware to processing chains
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn compose-xforms
  [xforms]
  (reduce (fn [fs {:keys [name log-diffs? handler] :as xform}]
            (let [f (if log-diffs?
                      (log-transformation-diffs xform))]
              (comp f fs)))
          identity
          xforms))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transform the incoming Ring request
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ring-request-pipeline-config
  [{:name       :add-request-identifier
    :handler    add-request-identifier
    ;;:log-diffs? true
    }
   {:name       :add-params-to-request
    :handler    params/params-request
    ;;:log-diffs? true
    }
   {:name       :add-multipart-params-to-request
    :handler    mp/multipart-params-request
    ;;:log-diffs? true
    }])

(defn ring-auth-pipeline-config
  [authentication-backend access-rules]
  [{:name       :authenticate-user
    :handler    (fn [request]
                  (apply ba/authentication-request request authentication-backend))
    :log-diffs? true}])

(def ring-request-pipeline
  (compose-xforms ring-request-pipeline-config))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Transform the outgoing Ring response
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ring-response-pipeline-config
  [{:name       :convert-response-to-json
    :handler    (fn [response]
                  (json-mw/json-response response {}))
    :log-diffs? true}
   {:name       :add-content-type-headers
    :handler    identity
    :log-diffs? true}
   {:name       :add-cache-control-headers
    :handler    identity
    :log-diffs? true}])

(def ring-response-pipeline
  (compose-xforms ring-response-pipeline-config))


(comment
  {:name    :enforce-access-rules
   :handler (fn [request]
              (ar/wrap-access-rules
               request
               {:rules          access-rules
                :reject-handler (fn [& args]
                                  (-> "Not authorized"
                                      (unauthorized)
                                      (resp/content-type "application/text")))}))}

  (ring-request-pipeline (mock/request :get "/endpoint")))
