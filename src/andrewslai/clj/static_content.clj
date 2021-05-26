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

(defmethod make-wrapper :DEFAULT
  [content-service-type root-path options]
  (make-wrapper "classpath" root-path options))

(defn get-wrapper-type
  "Where is the static content served from?
  Currently supported - `#{'classpath' 'filesystem'}`"
  [env]
  (get env "ANDREWSLAI_STATIC_CONTENT"))

(defn get-root-path
  [env]
  (get env "ANDREWSLAI_STATIC_CONTENT_BASE_URL" ""))

(defn configure-wrapper
  ([env]
   (configure-wrapper env {}))
  ([env options]
   (configure-wrapper (get-wrapper-type env)
                      (get-root-path env)
                      options))
  ([wrapper-type root-path options]
   (make-wrapper wrapper-type
                 root-path
                 options)))
