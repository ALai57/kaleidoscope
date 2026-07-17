(ns kaleidoscope.http-api.http-utils
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [kaleidoscope.http-api.cache-control :as cc]
   [kaleidoscope.persistence.filesystem :as fs]
   [ring.core.protocols :as ring-protocols]
   [ring.util.http-response :refer [not-found not-modified]]
   [taoensso.timbre :as log]))

(defn remove-port
  [hostname]
  (first (str/split hostname #":")))

(defn get-host
  [request]
  (-> request
      (get-in [:headers "host"])
      (remove-port)))

(defn kebab-case-headers
  [{:keys [headers] :as request}]
  (cond-> request
    headers (assoc :headers (cske/transform-keys csk/->kebab-case headers))))

(defn get-tenant
  "The resolved tenant identity — scopes DB queries. Set by wrap-resolve-tenant"
  [request] (:tenant request))

(def forced-store-key
  "Request key set by wrap-force-store when a route names a shared store." ::forced-store)

(defn asset-store
  "The store name that serves this request's files: a route-forced shared store
  wins, else the store resolved at the edge. No Host fallback — callers that skip
  the middleware (default handler) set forced-store-key explicitly."
  [request] (get request forced-store-key (:asset-store request)))

(defn get-resource
  [static-content-adapters {:keys [uri headers] :as request}]
  (log/infof "Getting resource at %s for %s" uri (asset-store request))
  (let [bucket  (asset-store request)
        adapter (get static-content-adapters bucket)
        result  (when adapter
                  (fs/get adapter uri (if-let [version (get-in request [:headers "if-none-match"])]
                                        {:version version}
                                        {})))]
    (cond
      (nil? adapter)              (do (log/warnf "Invalid request: no static-content adapter for store %s" bucket)
                                      {:status 404})
      (fs/folder? uri)            (-> {:status 200
                                       :body   result}
                                      (cc/cache-control uri))
      (fs/does-not-exist? result) (not-found)
      (fs/not-modified? result)   (not-modified)
      :else                       (-> {:status  200
                                       :headers {"ETag" (fs/object-version result)}
                                       :body    (fs/object-content result)}
                                      (cc/cache-control uri)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; HTTP responses
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def example-not-found
  {:reason "missing"})

(def ErrorResponse
  [:map])

(def NotFoundResponse
  [:map
   [:reason :string]])

(def NotAuthorizedResponse
  [:any])

(def example-not-authorized
  {})

(def openapi-401
  {404 {:description "Unauthorized"
        :content     {"application/json"
                      {:schema   NotAuthorizedResponse
                       :examples {"not-authorized" {:summary "Not authorized"
                                                    :value   example-not-authorized}}}}}})

(def openapi-404
  {404 {:description "Not found"
        :content     {"application/json"
                      {:schema   NotFoundResponse
                       :examples {"not-found" {:summary "Not found"
                                               :value   example-not-found}}}}}})

(def openapi-500
  {500 {:description "Error response"
        :content     {"application/json"
                      {:schema ErrorResponse}}}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Server-Sent Events (SSE)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn sse-response
  "Build a Ring SSE response. body-fn receives an OutputStream and writes events."
  [body-fn]
  {:status  200
   :headers {"Content-Type"      "text/event-stream"
             "Cache-Control"     "no-cache"
             "X-Accel-Buffering" "no"
             "Connection"        "keep-alive"}
   :body    (reify ring-protocols/StreamableResponseBody
              (write-body-to-stream [_ _ output-stream]
                (try
                  (body-fn output-stream)
                  (catch Exception e
                    (log/errorf "SSE stream error: %s" e)))))})

(defn write-sse-event!
  "Write a single SSE data event to an OutputStreamWriter."
  [^java.io.OutputStreamWriter writer data]
  (.write writer (str "data: " (json/encode data) "\n\n"))
  (.flush writer))
