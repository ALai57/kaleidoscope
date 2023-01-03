(ns andrewslai.clj.init.env
  "Parses environment variables into Clojure maps that are used to boot system
  components."
  (:require [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Environment variable helpers for Launch options
;;
;; Environment variables are used to launch the application
;; and change the configuration of the app. These helpers
;; parse the environment variables into `launch-options`
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn env->port
  [env]
  (Integer/parseInt (get env "ANDREWSLAI_PORT" "5000")))

(defn env->log-level
  [env]
  (keyword (get env "ANDREWSLAI_LOG_LEVEL" "info")))

(defn env->db-type
  [env]
  (keyword (get env "ANDREWSLAI_DB_TYPE" "postgres")))


;; Andrewslai app
(defn env->andrewslai-authentication-type
  [env]
  (keyword (get env "ANDREWSLAI_AUTH_TYPE" "keycloak")))

(defn env->andrewslai-authorization-type
  [env]
  (keyword (get env "ANDREWSLAI_AUTHORIZATION_TYPE" "keycloak")))

(defn env->andrewslai-static-content-type
  [env]
  (keyword (get env "ANDREWSLAI_STATIC_CONTENT_TYPE" "none")))


;; Wedding app
(defn env->wedding-static-content-type
  [env]
  (keyword (get env "ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" "none")))

(defn env->wedding-authentication-type
  [env]
  (keyword (get env "ANDREWSLAI_WEDDING_AUTH_TYPE" "keycloak")))

(defn env->wedding-authorization-type
  [env]
  (keyword (get env "ANDREWSLAI_WEDDING_AUTHORIZATION_TYPE" "keycloak")))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Launch Options Map
;; Parse environment variables into a map of `launch-options`:
;; the minimal amount of information needed to launch a webserver.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn environment->launch-options
  [env]
  (-> {:server/port         (env->port env)
       :logging/level       (env->log-level env)
       :database/type       (env->db-type env)

       :andrewslai/authentication-type (env->andrewslai-authentication-type env)
       :andrewslai/authorization-type  (env->andrewslai-authorization-type env)
       :andrewslai/static-content-type (env->andrewslai-static-content-type env)

       :wedding/authentication-type (env->wedding-authentication-type env)
       :wedding/authorization-type  (env->wedding-authorization-type env)
       :wedding/static-content-type (env->wedding-static-content-type env)
       }))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration for components
;;
;; After parsing into a `launch-options` map, the config
;; namespace will continue booting pieces/components of the system
;; depending on which launch options were selected
;;
;; e.g. if `:keycloak` authentication was selected,
;;      the `env->keycloak` helper will parse relevant
;;      keycloak environment variables into a configuration
;;      map that can be used to boot the keycloak component.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn env->keycloak
  [env]
  {:realm             (get env "ANDREWSLAI_AUTH_REALM")
   :auth-server-url   (get env "ANDREWSLAI_AUTH_URL")
   :client-id         (get env "ANDREWSLAI_AUTH_CLIENT")
   :client-secret     (get env "ANDREWSLAI_AUTH_SECRET")
   :ssl-required      "external"
   :confidential-port 0})

(defn env->pg-conn
  [env]
  {:dbname   (get env "ANDREWSLAI_DB_NAME")
   :db-port  (get env "ANDREWSLAI_DB_PORT" "5432")
   :host     (get env "ANDREWSLAI_DB_HOST")
   :user     (get env "ANDREWSLAI_DB_USER")
   :password (get env "ANDREWSLAI_DB_PASSWORD")
   :dbtype   "postgresql"})

(defn env->andrewslai-s3-bucket
  [env]
  (get env "ANDREWSLAI_BUCKET" "andrewslai"))

(defn env->andrewslai-local-static-content-folder
  [env]
  (get env "ANDREWSLAI_STATIC_CONTENT_FOLDER" "resources/public"))

(defn env->wedding-s3-bucket
  [env]
  (get env "ANDREWSLAI_WEDDING_BUCKET" "andrewslai-wedding"))

(defn env->wedding-local-static-content-folder
  [env]
  (get env "ANDREWSLAI_WEDDING_STATIC_CONTENT_FOLDER" "resources/public"))

(comment
  (environment->launch-options (System/getenv))
  )
