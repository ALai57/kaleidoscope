(ns andrewslai.clj.config
  (:require [andrewslai.clj.auth.core :as auth]
            [andrewslai.clj.auth.keycloak :as keycloak]
            [andrewslai.clj.persistence.postgres2 :as pg]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [andrewslai.clj.utils.files.protocols.core :as protocols]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.http-api.static-content :as sc]
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

(defn configure-auth
  "Is OAUTH is disabled, always authenticate as a user with `wedding` access"
  [env]
  (if (Boolean/parseBoolean (get env "ANDREWSLAI_OAUTH_DISABLED"))
    (auth/always-authenticated-backend {:realm_access {:roles ["wedding"]}})
    (configure-keycloak env)))

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
    (sc/static-content (s3-storage/map->S3 {:bucket bucket
                                            :creds  s3-storage/CustomAWSCredentialsProviderChain}))))

(defn configure-wedding-storage
  [env]
  (let [bucket (get env "ANDREWSLAI_WEDDING_BUCKET" "andrewslai-wedding")]
    (s3-storage/map->S3 {:bucket bucket
                         :creds  s3-storage/CustomAWSCredentialsProviderChain})))

(defn configure-wedding-access
  [env]
  wedding/access-rules)

(defn configure-from-env
  [env]
  {:port       (configure-port env)
   :andrewslai {:auth           (configure-auth env)
                :database       (configure-database env)
                :logging        (configure-logging env)
                :static-content (configure-frontend-bucket env)}
   :wedding    {:auth         (configure-auth env)
                :access-rules (configure-wedding-access env)
                :storage      (configure-wedding-storage env)
                :logging      (configure-logging env)}})
