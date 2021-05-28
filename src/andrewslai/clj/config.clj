(ns andrewslai.clj.config
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [andrewslai.clj.protocols.s3 :as s3p]
            [andrewslai.clj.static-content :as sc]
            [taoensso.timbre :as log]))

(def DEFAULT-STATIC-CONTENT
  "classpath")

(def DEFAULT-STATIC-CONTENT-LOCATION
  {"classpath"  "public/"
   "filesystem" "assets/public/"
   "s3"         ""})

(defn configure-port
  [env]
  (int (get env "ANDREWSLAI_PORT" 5000)))

(defn configure-keycloak
  [env]
  (-> {:realm             (get env "ANDREWSLAI_AUTH_REALM")
       :auth-server-url   (get env "ANDREWSLAI_AUTH_URL")
       :client-id         (get env "ANDREWSLAI_AUTH_CLIENT")
       :client-secret     (get env "ANDREWSLAI_AUTH_SECRET")
       :ssl-required      "external"
       :confidential-port 0}
      keycloak/make-keycloak
      auth/oauth-backend))

(defn configure-static-content
  ([env]
   (configure-static-content env {}))
  ([env options]
   (let [content-service-type (get env "ANDREWSLAI_STATIC_CONTENT" DEFAULT-STATIC-CONTENT)]
     (configure-static-content content-service-type
                               (or (get env "ANDREWSLAI_STATIC_CONTENT_BASE_URL")
                                   (get DEFAULT-STATIC-CONTENT-LOCATION content-service-type)
                                   (throw (IllegalArgumentException. "Invalid static content url")))
                               options)))
  ([wrapper-type root-path options]
   (sc/make-wrapper wrapper-type
                    root-path
                    options)))

(defn configure-logging
  [env]
  (merge log/*config* {:level (keyword (get env "ANDREWSLAI_LOG_LEVEL" "info"))}))

(defn configure-database
  [env]
  (pg/->Database {:dbname   (get env "ANDREWSLAI_DB_NAME")
                  :db-port  (get env "ANDREWSLAI_DB_PORT" "5432")
                  :host     (get env "ANDREWSLAI_DB_HOST")
                  :user     (get env "ANDREWSLAI_DB_USER")
                  :password (get env "ANDREWSLAI_DB_PASSWORD")
                  :dbtype   "postgresql"}))

(defn configure-frontend-bucket
  [env]
  (let [bucket (get env "ANDREWSLAI_BUCKET" "andrewslai")]
    (sc/make-wrapper "s3"
                     ""
                     {:loader (-> {:bucket bucket
                                   :creds  s3-storage/CustomAWSCredentialsProviderChain}
                                  s3-storage/map->S3
                                  s3p/s3-loader)
                      :prefer-handler? true})))

(defn configure-wedding-bucket
  [env]
  (let [bucket (get env "ANDREWSLAI_WEDDING_BUCKET" "andrewslai-wedding")]
    (sc/make-wrapper "s3"
                     ""
                     {:loader (-> {:bucket bucket
                                   :creds  s3-storage/CustomAWSCredentialsProviderChain}
                                  s3-storage/map->S3
                                  s3p/s3-loader)
                      :prefer-handler? true})))

(defn configure-from-env
  [env]
  {:port            (configure-port env)
   :auth            (configure-keycloak env)
   :database        (configure-database env)
   :wedding-storage (configure-wedding-bucket env)
   :logging         (configure-logging env)
   :static-content  (configure-frontend-bucket env)})
