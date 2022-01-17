(ns andrewslai.clj.config
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres :as pg]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [andrewslai.clj.utils.files.protocols.core :as protocols]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.http-api.static-content :as sc]
            [andrewslai.clj.persistence.embedded-postgres :as embedded-pg]
            [andrewslai.clj.persistence.embedded-h2 :as embedded-h2]
            [taoensso.timbre :as log]))

(log/merge-config! {:min-level :info})

(def DEFAULT-STATIC-CONTENT
  "classpath")

(def DEFAULT-STATIC-CONTENT-LOCATION
  {"classpath"  "public/"
   "filesystem" "assets/public/"
   "s3"         ""})

(defn configure-port
  [env]
  (Integer/parseInt (get env "ANDREWSLAI_PORT" "5000")))

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

(defn configure-auth
  "Is OAUTH is disabled, always authenticate as a user with `wedding` access"
  [env]
  (case (get env "ANDREWSLAI_AUTH_TYPE" "keycloak")
    "keycloak" (configure-keycloak env)
    "none"     (auth/always-authenticated-backend {:realm_access {:roles ["wedding"]}})))

(defn configure-logging
  [env]
  (merge log/*config* {:min-level (keyword (get env "ANDREWSLAI_LOG_LEVEL" "info"))}))

(defn configure-database
  [env]
  (case (get env "ANDREWSLAI_DB_TYPE" "postgres")
    "postgres"          (pg/->NextDatabase (rdbms/get-datasource (pg/pg-conn env)))
    "embedded-postgres" (pg/->NextDatabase (embedded-pg/fresh-db!))
    "embedded-h2"       (pg/->NextDatabase (embedded-h2/fresh-db!))))

(defn configure-static-content
  [env]
  (case (get env "ANDREWSLAI_STATIC_CONTENT_TYPE" "s3")
    "s3"    (sc/static-content (s3-storage/map->S3 {:bucket (get env "ANDREWSLAI_BUCKET" "andrewslai")
                                                    :creds  s3-storage/CustomAWSCredentialsProviderChain}))
    "local" (sc/file-static-content-wrapper (get env "ANDREWSLAI_STATIC_CONTENT_FOLDER" "resources/public") {})))

(defn configure-wedding-storage
  [env]
  (s3-storage/map->S3 {:bucket (get env "ANDREWSLAI_WEDDING_BUCKET" "andrewslai-wedding")
                       :creds  s3-storage/CustomAWSCredentialsProviderChain}))

(defn configure-wedding-access
  [env]
  wedding/access-rules)

(defn configure-from-env
  [env]
  {:port       (configure-port env)
   :andrewslai {:auth           (configure-auth env)
                :database       (configure-database env)
                :logging        (configure-logging env)
                :static-content (configure-static-content env)}
   :wedding    {:auth         (configure-auth env)
                :access-rules (configure-wedding-access env)
                :storage      (configure-wedding-storage env)
                :logging      (configure-logging env)}})
