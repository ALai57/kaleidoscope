(ns andrewslai.clj.static-content
  (:require [andrewslai.clj.persistence.memory :as memory]
            [andrewslai.clj.protocols.core :as protocols]
            [clojure.string :as string]
            [ring.util.http-predicates :refer [success?]]
            [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]
            [taoensso.timbre :as log]))

(def no-cache  "max-age=0,no-cache,no-store")
(def cache-30d "public,max-age=2592000,s-maxage=2592000")

(defn cache-control
  "Add Cache Control Headers for successful responses"
  [url response]
  (cond
    (nil? response)     (log/info "Cache control: No matched route for request!")
    (success? response) (cond
                          (string/ends-with? url ".html") (assoc-in response [:headers "Cache-Control"] no-cache)
                          (string/ends-with? url "/")     (assoc-in response [:headers "Cache-Control"] no-cache)
                          :else                           (assoc-in response [:headers "Cache-Control"] cache-30d))
    :else               response))

(defn wrap-cache-control
  "Wraps responses with a cache-control header"
  [handler]
  (fn [request]
    (let [resp (handler request)]
      (log/infof "Generating Cache control headers for request-id %s\n"
                 (:request-id request))
      (cache-control (:uri request) resp))))

(defn classpath-static-content-wrapper
  ([options]
   (classpath-static-content-wrapper "" options))
  ([root-path options]
   (fn [handler]
     (wrap-cache-control (wrap-resource handler root-path options)))))

(defn file-static-content-wrapper
  [root-path options]
  (fn [handler]
    (wrap-cache-control (wrap-file handler root-path options))))

(defn static-content
  "Returns Middleware that serves static content from a filesystem"
  [filesystem]
  (classpath-static-content-wrapper
   {:loader          (protocols/filesystem-loader filesystem)
    :prefer-handler? true}))
