(ns andrewslai.clj.init.config
  (:require [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.http-api.virtual-hosting :as vh]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.persistence.filesystem.url-utils :as url-utils]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [andrewslai.clj.persistence.rdbms.live-pg :as live-pg]
            [andrewslai.clj.init.env :as env]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

(def public-access (constantly true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Desired
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def launch-options
  (env/environment->launch-options (System/getenv))

  #_{:port       12121
     :andrewslai {:auth          :auth-type
                  :access-type   :access-type
                  :database-type :db-type
                  :storage-type  :storage-type}
     :wedding    {:auth          :auth-type
                  :access-type   :access-type
                  :database-type :db-type
                  :storage-type  :storage-type}})

(defn make-logging
  [{:logging/keys [level] :as launch-options} env]
  (merge log/*config* {:min-level level}))

(defn make-database-connection
  [{:database/keys [type] :as launch-options} env]
  (case type
    :postgres          (next/get-datasource (env/env->pg-conn env))
    :embedded-postgres (embedded-pg/fresh-db!)
    :embedded-h2       (embedded-h2/fresh-db!)))

;; Authentication backends
(defn make-keycloak
  [env]
  (-> env
      (env/env->keycloak)
      (bb/keycloak-backend)))

(defn make-authentication-backend
  [{:authentication/keys [type] :as launch-options} env]
  (case type
    :keycloak                     (make-keycloak env)
    :authenticated-only           (bb/authenticated-backend {:name         "Test User"
                                                             :realm_access {:roles []}})
    :authenticated-and-authorized (bb/authenticated-backend {:name         "Test User"
                                                             :realm_access {:roles ["wedding" "andrewslai"]}})))

;; Static content serving
(defn make-andrewslai-static-content-fetcher
  [{:andrewslai.static-content/keys [type] :as launch-options} env]
  (case type
    :s3    (mw/classpath-static-content-stack
            {:root-path       ""
             :prefer-handler? true
             :loader          (-> {:bucket (env/env->andrewslai-s3-bucket env)
                                   :creds  s3-storage/CustomAWSCredentialsProviderChain}
                                  s3-storage/map->S3
                                  url-utils/filesystem-loader)})
    :local (mw/file-static-content-stack
            {:root-path (env/env->andrewslai-local-static-content-folder env)})
    :none  identity))

(defn make-wedding-static-content-fetcher
  [{:andrewslai.static-content/keys [type] :as launch-options} env]
  (case type
    :s3    (mw/classpath-static-content-stack
            {:root-path       ""
             :prefer-handler? true
             :loader          (-> {:bucket (env/env->wedding-s3-bucket env)
                                   :creds  s3-storage/CustomAWSCredentialsProviderChain}
                                  s3-storage/map->S3
                                  url-utils/filesystem-loader)})
    :local (mw/file-static-content-stack
            {:root-path (env/env->wedding-local-static-content-folder env)})
    :none  identity))

;; Access control
(defn make-andrewslai-authorization
  [{:andrewslai.authorization/keys [type] :as launch-options} env]
  (case type
    :allow-authenticated-users []
    :use-access-control-list   [{:pattern #"^/admin.*" :handler (partial auth/require-role "andrewslai")}
                                {:pattern #"^/articles.*" :handler (partial auth/require-role "andrewslai")}
                                {:pattern #"^/branches.*" :handler (partial auth/require-role "andrewslai")}
                                {:pattern #"^/compositions.*" :handler public-access}
                                {:pattern #"^/$" :handler public-access}
                                {:pattern #"^/index.html$" :handler public-access}
                                {:pattern #"^/ping" :handler public-access}
                                #_{:pattern #"^/.*" :handler (constantly false)}]))

(defn make-wedding-authorization
  [{:wedding.authorization/keys [type] :as launch-options} _env]
  (case type
    :allow-authenticated-users []
    :use-access-control-list   [{:pattern #"^/media.*" :handler (partial auth/require-role "wedding")}
                                {:pattern #"^/albums.*" :handler (partial auth/require-role "wedding")}]))

(defn make-middleware
  [{:keys [static-content-fetcher] :as components} _env]
  (comp mw/standard-stack
        static-content-fetcher
        (mw/auth-stack components)))

(defn initialize-system!
  [launch-options env]
  (let [authentication-backend            (make-authentication-backend launch-options env)
        database-connection               (make-database-connection launch-options env)
        logging                           (make-logging launch-options env)

        andrewslai-authorization          (make-andrewslai-authorization launch-options env)
        andrewslai-static-content-fetcher (make-andrewslai-static-content-fetcher launch-options env)
        andrewslai-middleware             (make-middleware {:static-content-fetcher andrewslai-static-content-fetcher} env)

        wedding-authorization             (make-wedding-authorization launch-options env)
        wedding-static-content-fetcher    (make-wedding-static-content-fetcher launch-options env)
        andrewslai-middleware             (make-middleware {:static-content-fetcher wedding-static-content-fetcher} env)
        ]
    {:port       (:server/port launch-options)
     :andrewslai {:auth         authentication-backend
                  :database     database-connection

                  :access-rules andrewslai-authorization
                  :storage      andrewslai-static-content-fetcher
                  :http-mw      andrewslai-middleware}
     :wedding    {:auth         authentication-backend
                  :database     database-connection

                  :access-rules wedding-authorization
                  :storage      wedding-static-content-fetcher
                  :logging      logging}}))

(defn make-http-handler
  [{:keys [andrewslai wedding] :as components}]
  (vh/host-based-routing
   {#"caheriaguilar.and.andrewslai.com" {:priority 0
                                         :app      (wedding/wedding-app wedding)}
    #".*"                               {:priority 100
                                         :app      (andrewslai/andrewslai-app andrewslai)}}))

(comment

  (initialize-system! {:server/port         5000
                       :logging/level       :info
                       :authentication/type :authenticated-only
                       :database/type       :embedded-h2

                       :andrewslai.authorization/type  :allow-authenticated-users
                       :andrewslai.static-content/type :none

                       :wedding.authorization/type  :allow-authenticated-users
                       :wedding.static-content/type :none}
                      (System/getenv))
  )
