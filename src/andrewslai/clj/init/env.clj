(ns andrewslai.clj.init.env
  "Parses environment variables into a configuration map"
  (:require [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]))

(defn merge-if
  [m pred m2]
  (if (pred m)
    (merge m m2)
    m))

;; Predicates
(defn keycloak-auth?
  [m]
  (= "keycloak" (:auth/type m)))

(defn postgres-db?
  [m]
  (= "postgres" (:database/type m)))

(defn andrewslai-local-static-content?
  [m]
  (= "local" (:andrewslai.static-content/type m)))

(defn andrewslai-s3-static-content?
  [m]
  (= "s3" (:andrewslai.static-content/type m)))

(defn wedding-s3-static-content?
  [m]
  (= "s3" (:wedding.static-content/type m)))

;; Configuration map
(defn environment->cfg-map
  [env]
  (-> {:server/port                    (Integer/parseInt (get env "ANDREWSLAI_PORT" "5000"))
       :logging/level                  (keyword (get env "ANDREWSLAI_LOG_LEVEL" "info"))
       :auth/type                      (get env "ANDREWSLAI_AUTH_TYPE" "keycloak")
       :database/type                  (get env "ANDREWSLAI_DB_TYPE" "postgres")
       :wedding.static-content/type    (get env "ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" "none")
       :andrewslai.static-content/type (get env "ANDREWSLAI_STATIC_CONTENT_TYPE" "none")}
      (merge-if keycloak-auth?
                {:keycloak/realm             (get env "ANDREWSLAI_AUTH_REALM")
                 :keycloak/auth-server-url   (get env "ANDREWSLAI_AUTH_URL")
                 :keycloak/client-id         (get env "ANDREWSLAI_AUTH_CLIENT")
                 :keycloak/client-secret     (get env "ANDREWSLAI_AUTH_SECRET")
                 :keycloak/ssl-required      "external"
                 :keycloak/confidential-port 0})
      (merge-if postgres-db?
                {:postgres/dbname   (get env "ANDREWSLAI_DB_NAME")
                 :postgres/db-port  (get env "ANDREWSLAI_DB_PORT" "5432")
                 :postgres/host     (get env "ANDREWSLAI_DB_HOST")
                 :postgres/user     (get env "ANDREWSLAI_DB_USER")
                 :postgres/password (get env "ANDREWSLAI_DB_PASSWORD")})
      (merge-if andrewslai-local-static-content?
                {:andrewslai.local-fs/folder (get env "ANDREWSLAI_STATIC_CONTENT_FOLDER" "resources/public")})
      (merge-if andrewslai-s3-static-content?
                {:andrewslai.s3/bucket (get env "ANDREWSLAI_BUCKET" "andrewslai")})
      (merge-if wedding-s3-static-content?
                {:wedding.s3/bucket (get env "ANDREWSLAI_WEDDING_BUCKET" "andrewslai-wedding")})


      ))

(defn from-env
  [env k default]
  (keyword (get env k default)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Environment variable helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn env->port
  [env]
  (Integer/parseInt (get env "ANDREWSLAI_PORT" "5000")))

(defn env->log-level
  [env]
  (keyword (get env "ANDREWSLAI_LOG_LEVEL" "info")))

(defn env->auth-type
  [env]
  (keyword (get env "ANDREWSLAI_AUTH_TYPE" "keycloak")))

(defn env->db-type
  [env]
  (keyword (get env "ANDREWSLAI_DB_TYPE" "postgres")))

(defn env->wedding-static-content-type
  [env]
  (keyword (get env "ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" "none")))

(defn env->andrewslai-static-content-type
  [env]
  (keyword (get env "ANDREWSLAI_STATIC_CONTENT_TYPE" "none")))

(defn environment->launch-options
  [env]
  (-> {:server/port         (env->port env)
       :logging/level       (env->log-level env)
       :authentication/type (env->auth-type env)
       :database/type       (env->db-type env)

       :andrewslai.static-content/type (env->andrewslai-static-content-type env)
       :andrewslai.authorization/type  (env->andrewslai-static-content-type env)

       :wedding.static-content/type (env->wedding-static-content-type env)
       :wedding.authorization/type  (env->wedding-static-content-type env)}
      ))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration for components
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
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
