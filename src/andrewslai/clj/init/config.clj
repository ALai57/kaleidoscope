(ns andrewslai.clj.init.config
  (:require [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.http-api.virtual-hosting :as vh]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.persistence.filesystem.in-memory-impl :as memory]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [andrewslai.clj.persistence.rdbms.live-pg :as live-pg]
            [andrewslai.clj.init.env :as env]
            [next.jdbc :as next]
            [taoensso.timbre :as log]
            [andrewslai.clj.test-utils :as tu]))

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

(defn make-andrewslai-authentication-backend
  [{:andrewslai/keys [authentication-type custom-authenticated-user] :as launch-options} env]
  (case authentication-type
    :keycloak                     (make-keycloak env)
    :always-unauthenticated       bb/unauthenticated-backend
    :always-authenticated         (bb/authenticated-backend {:name         "Test User"
                                                             :realm_access {:roles []}})
    :custom-authenticated-user    (bb/authenticated-backend custom-authenticated-user)
    :authenticated-and-authorized (bb/authenticated-backend {:name         "Test User"
                                                             :realm_access {:roles ["andrewslai"]}})))

(defn make-wedding-authentication-backend
  [{:wedding/keys [authentication-type custom-authenticated-user] :as launch-options} env]
  (case authentication-type
    :keycloak                     (make-keycloak env)
    :always-unauthenticated       bb/unauthenticated-backend
    :always-authenticated         (bb/authenticated-backend {:name         "Test User"
                                                             :realm_access {:roles []}})
    :custom-authenticated-user    (bb/authenticated-backend custom-authenticated-user)
    :authenticated-and-authorized (bb/authenticated-backend {:name         "Test User"
                                                             :realm_access {:roles ["wedding"]}})))

;; Static content Adapter (a Filesystem-like object)
(def example-fs
  "An in-memory filesystem used for testing"
  {"index.html" (memory/file {:name    "index.html"
                              :content "<div>Hello</div>"})})

;; TODO: Update in-memory
(defn make-andrewslai-static-content-adapter
  [{:andrewslai/keys [static-content-type] :as launch-options} env]
  (case static-content-type
    :s3               (s3-storage/map->S3 {:bucket (env/env->andrewslai-s3-bucket env)
                                           :creds  s3-storage/CustomAWSCredentialsProviderChain})
    :in-memory        (memory/map->MemFS {:store (atom example-fs)})
    :local-filesystem identity #_ (mw/file-static-content-stack
                                   {:root-path (env/env->andrewslai-local-static-content-folder env)})
    :none             identity))

(defn make-wedding-static-content-adapter
  [{:wedding/keys [static-content-type] :as launch-options} env]
  (case static-content-type
    :s3               (s3-storage/map->S3 {:bucket (env/env->wedding-s3-bucket env)
                                           :creds  s3-storage/CustomAWSCredentialsProviderChain})
    :in-memory        (memory/map->MemFS {:store (atom example-fs)})
    :local-filesystem identity #_ (mw/file-static-content-stack
                                   {:root-path (env/env->andrewslai-local-static-content-folder env)})
    :none             identity))

;; Access control
(defn make-andrewslai-authorization
  [{:andrewslai/keys [authorization-type] :as launch-options} env]
  (case authorization-type
    :public-access             tu/public-access
    ;;:allow-authenticated-users []
    :use-access-control-list   [{:pattern #"^/admin.*" :handler (partial auth/require-role "andrewslai")}
                                {:pattern #"^/articles.*" :handler (partial auth/require-role "andrewslai")}
                                {:pattern #"^/branches.*" :handler (partial auth/require-role "andrewslai")}
                                {:pattern #"^/compositions.*" :handler public-access}
                                {:pattern #"^/$" :handler public-access}
                                {:pattern #"^/index.html$" :handler public-access}
                                {:pattern #"^/ping" :handler public-access}
                                #_{:pattern #"^/.*" :handler (constantly false)}]))

(defn make-andrewslai-middleware
  "Middleware handles Authentication, Authorization"
  [launch-options env]
  (let [authentication-backend (make-andrewslai-authentication-backend launch-options env)
        access-rules           (make-andrewslai-authorization launch-options env)]
    (comp mw/standard-stack
          (mw/auth-stack authentication-backend access-rules))))

(defn make-wedding-authorization
  [{:wedding/keys [authorization-type custom-access-rules] :as launch-options} _env]
  (case authorization-type
    :public-access           tu/public-access
    :custom-access-rules     custom-access-rules
    :use-access-control-list [{:pattern #"^/media.*" :handler (partial auth/require-role "wedding")}
                              {:pattern #"^/albums.*" :handler (partial auth/require-role "wedding")}]))

;; REMOVING STATIC CONTENT FETCHER HERE
(defn make-wedding-middleware
  "Middleware handles Authentication, Authorization"
  ([launch-options]
   (make-wedding-middleware launch-options {}))
  ([launch-options env]
   (let [authentication-backend (make-wedding-authentication-backend launch-options env)
         access-rules           (make-wedding-authorization launch-options env)]
     (comp mw/standard-stack
           (mw/auth-stack authentication-backend access-rules)))))

(defn initialize-system!
  [launch-options env]
  (let [database-connection (make-database-connection launch-options env)
        logging             (make-logging launch-options env)

        andrewslai-middleware             (make-andrewslai-middleware launch-options env)
        andrewslai-static-content-adapter (make-andrewslai-static-content-adapter launch-options env)

        wedding-middleware             (make-wedding-middleware launch-options env)
        wedding-static-content-adapter (make-andrewslai-static-content-adapter launch-options env)]
    {:andrewslai {:database               database-connection
                  :http-mw                andrewslai-middleware
                  :static-content-adapter andrewslai-static-content-adapter}
     :wedding    {:database               database-connection
                  :http-mw                wedding-middleware
                  :static-content-adapter wedding-static-content-adapter
                  :logging                logging}}))

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
