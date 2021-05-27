(ns andrewslai.clj.static-content
  (:require [ring.middleware.file :refer [wrap-file]]
            [ring.middleware.resource :refer [wrap-resource]]))

(defmulti make-wrapper (fn [content-service-type root-path options]
                         content-service-type))


;; (or root-path "public/")
(defmethod make-wrapper "classpath"
  [content-service-type root-path options]
  (fn [handler]
    (wrap-resource handler root-path options)))

;; (or root-path "resources/public")
(defmethod make-wrapper "filesystem"
  [content-service-type root-path options]
  (fn [handler]
    (wrap-file handler root-path options)))

(defmethod make-wrapper "s3"
  [content-service-type root-path options]
  (fn [handler]
    (wrap-resource handler root-path options)))

(defmethod make-wrapper "mem"
  [content-service-type root-path options]
  (fn [handler]
    (wrap-resource handler root-path options)))

(defmethod make-wrapper :DEFAULT
  [content-service-type root-path options]
  (make-wrapper "classpath" root-path options))

