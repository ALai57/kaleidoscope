(ns andrewslai.clj.init.config
  (:require [andrewslai.clj.api.auth :as auth]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.http-api.static-content :as sc]
            [andrewslai.clj.persistence.embedded-h2 :as embedded-h2]
            [andrewslai.clj.persistence.embedded-postgres :as embedded-pg]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.rdbms :as rdbms]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [taoensso.timbre :as log]))

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
      (bb/keycloak-backend)))

(defn configure-auth
  "Is OAUTH is disabled, always authenticate as a user with `wedding` access"
  [env]
  (case (get env "ANDREWSLAI_AUTH_TYPE" "keycloak")
    "keycloak" (configure-keycloak env)
    "none"     (bb/authenticated-backend {:realm_access {:roles ["wedding"]}})))

(defn configure-logging
  [env]
  (merge log/*config* {:min-level (keyword (get env "ANDREWSLAI_LOG_LEVEL" "info"))}))

(defn configure-database
  [env]
  (case (get env "ANDREWSLAI_DB_TYPE" "postgres")
    "postgres"          (rdbms/get-datasource (rdbms/pg-conn env))
    "embedded-postgres" (embedded-pg/fresh-db!)
    "embedded-h2"       (embedded-h2/fresh-db!)))

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

(defn configure-access
  [_env]
  [{:pattern #"^/admin.*"
    :handler (partial auth/require-role "andrewslai")}
   {:request-method :put
    :pattern        #"^/articles/.*"
    :handler        (partial auth/require-role "andrewslai")}
   ])

(defn configure-wedding-access
  [_env]
  [{:pattern #"^/media.*"
    :handler (partial auth/require-role "wedding")}
   {:pattern #"^/albums.*"
    :handler (partial auth/require-role "wedding")}])

(defn add-andrewslai-middleware
  [{:keys [static-content] :as andrewslai-components}]
  (assoc andrewslai-components
         :http-mw
         (comp mw/standard-stack
               mw/log-request!
               (or static-content identity)
               (mw/auth-stack andrewslai-components))))

(defn add-wedding-middleware
  [{:keys [storage] :as wedding-components}]
  (assoc wedding-components
         :http-mw (comp mw/standard-stack
                        mw/params-stack
                        mw/log-request!
                        (sc/static-content storage)
                        (mw/auth-stack wedding-components))))

(defn configure-wedding-middleware
  [components]
  (update components
          :wedding
          add-wedding-middleware))

(defn configure-from-env
  [env]
  (-> {:port       (configure-port env)
       :andrewslai {:auth           (configure-auth env)
                    :access-rules   (configure-access env)
                    :database       (configure-database env)
                    :static-content (configure-static-content env)
                    :http-mw        []}
       :wedding    {:auth         (configure-auth env)
                    :access-rules (configure-wedding-access env)
                    :database     (configure-database env)
                    :storage      (configure-wedding-storage env)
                    :logging      (configure-logging env)}}
      (update :wedding add-wedding-middleware)
      (update :andrewslai add-andrewslai-middleware)))
