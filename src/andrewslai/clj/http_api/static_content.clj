(ns andrewslai.clj.http-api.static-content
  (:require [andrewslai.clj.utils.files.protocols.core :as protocols]
            [andrewslai.clj.utils.core :as u]
            [clojure.string :as string]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]
            [ring.util.http-predicates :refer [success?]]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cache control helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def no-cache  "max-age=0,no-cache,no-store")
(def cache-30d "public,max-age=2592000,s-maxage=2592000")

(def url-caching-policy
  [[#"\.png$" cache-30d]
   [#"\.svg$" cache-30d]])

(defn cache-control-header
  "Find the correct cache control headers for a given URL.
  Useful because we are serving static content from S3 - so some content should have
  long caching (images) while others should not (actual site)."
  [url]
  (u/find-first-match url-caching-policy url no-cache))

(defn add-cache-control
  [m v]
  (assoc-in m [:headers "Cache-Control"] v))

(defn cache-control
  "Add Cache Control Headers for successful responses"
  [url response]
  (if (success? response)
    (add-cache-control response (cache-control-header url))
    response))

(defn wrap-cache-control
  "Wraps responses with a cache-control header"
  [handler]
  (fn [{:keys [request-id uri] :as request}]
    (cache-control uri (let [response (handler request)]
                         (log/infof "Generating Cache control headers for request-id %s\n" request-id)
                         response))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Perhaps change endings to `static-content-middleware`
(defn classpath-static-content-wrapper
  "Returns middleware that intercepts requests and serves files from the
  ClassLoader's Classpath."
  ([options]
   (classpath-static-content-wrapper "" options))
  ([root-path options]
   (fn [handler]
     (-> handler
         (wrap-resource root-path options)
         (wrap-cache-control)))))

(defn file-static-content-wrapper
  "Returns middleware that intercepts requests and serves files relative to
  the root path."
  [root-path options]
  (fn [handler]
    (-> handler
        (wrap-cache-control)
        (wrap-file root-path options))))

(defn static-content
  "Returns Middleware that serves static content from a filesystem
  implementing the FileSystem protocol"
  [filesystem]
  (classpath-static-content-wrapper {:loader          (protocols/filesystem-loader filesystem)
                                     :prefer-handler? true}))
