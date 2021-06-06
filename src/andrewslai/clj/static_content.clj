(ns andrewslai.clj.static-content
  (:require [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]
            [clojure.string :as string]))

(def no-cache     "max-age=0,no-cache,no-store")
(def cache-30d    "public,max-age=2592000,s-maxage=2592000")

(defn cache-control
  [url response]
  (cond
    (string/ends-with? url ".html") (assoc-in response [:headers "Cache-Control"] no-cache)
    (string/ends-with? url "/")     (assoc-in response [:headers "Cache-Control"] no-cache)
    :else                           (assoc-in response [:headers "Cache-Control"] cache-30d)))

(defn classpath-static-content-wrapper
  ([options]
   (classpath-static-content-wrapper "" options))
  ([root-path options]
   (fn [handler]
     (fn [request]
       (cache-control (:uri request)
                      ((wrap-resource handler root-path options) request))))))

(defn file-static-content-wrapper
  [root-path options]
  (fn [handler]
    (fn [request]
      (cache-control (:uri request)
                     ((wrap-file handler root-path options) request)))))
