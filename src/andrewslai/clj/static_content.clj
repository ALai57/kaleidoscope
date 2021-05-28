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

(defmulti make-wrapper (fn [content-service-type root-path options]
                         content-service-type))


;; (or root-path "public/")
(defmethod make-wrapper "classpath"
  [content-service-type root-path options]
  (fn [handler]
    (fn [request]
      (cache-control (:uri request)
                     ((wrap-resource handler root-path options) request)))))

;; (or root-path "resources/public")
(defmethod make-wrapper "filesystem"
  [content-service-type root-path options]
  (fn [handler]
    (fn [request]
      (cache-control (:uri request)
                     ((wrap-file handler root-path options) request)))))

(defmethod make-wrapper "s3"
  [content-service-type root-path options]
  (fn [handler]
    (fn [request]
      (cache-control (:uri request)
                     ((wrap-resource handler root-path options) request)))))

(defmethod make-wrapper "mem"
  [content-service-type root-path options]
  (fn [handler]
    (fn [request]
      (cache-control (:uri request)
                     ((wrap-resource handler root-path options) request)))))

(defmethod make-wrapper :DEFAULT
  [content-service-type root-path options]
  (make-wrapper "classpath" root-path options))

