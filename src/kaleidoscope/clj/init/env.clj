(ns kaleidoscope.clj.init.env
  "Parses environment variables into Clojure maps that are used to boot system
  components."
  (:require [kaleidoscope.clj.http-api.andrewslai :as andrewslai]
            [kaleidoscope.clj.http-api.auth.buddy-backends :as bb]
            [kaleidoscope.clj.http-api.caheriaguilar :as caheriaguilar]
            [kaleidoscope.clj.http-api.sahiltalkingcents :as sahiltalkingcents]
            [kaleidoscope.clj.http-api.middleware :as mw]
            [kaleidoscope.clj.http-api.virtual-hosting :as vh]
            [kaleidoscope.clj.http-api.wedding :as wedding]
            [kaleidoscope.clj.persistence.filesystem.in-memory-impl :as memory]
            [kaleidoscope.clj.persistence.filesystem.local :as local-fs]
            [kaleidoscope.clj.persistence.filesystem.s3-impl :as s3-storage]
            [kaleidoscope.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.clj.persistence.rdbms.embedded-postgres-impl
             :as embedded-pg]
            [kaleidoscope.clj.test-utils :as tu]
            [malli.core :as m]
            [malli.dev.pretty :as pretty]
            [malli.dev.virhe :as v]
            [malli.instrument :as mi]
            [next.jdbc :as next]
            [next.jdbc.connection :as connection]
            [taoensso.timbre :as log])
  (:import (com.zaxxer.hikari HikariDataSource))
  )

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Boot instructions for starting system components from the environment
;;
;; After parsing into a `launch-options` map, the config
;; namespace will continue booting pieces/components of the system
;; depending on which launch options were selected
;;
;; e.g. if `[:andrewslai :authentication-type]` of `:keycloak` was selected,
;;      the `init-andrewslai-keycloak` helper will start a `bb/keycloak-backend`
;;      by parsing relevant keycloak environment variables into a configuration
;;      map (`env->keycloak`) that can be used to boot the keycloak component.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn env->keycloak
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:realm             [:string {:error/message "Missing Keycloak realm. Set via KALEIDOSCOPE_AUTH_REALM environment variable."}]]
                   [:auth-server-url   [:string {:error/message "Missing Keycloak auth server url. Set via KALEIDOSCOPE_AUTH_URL environment variable."}]]
                   [:client-id         [:string {:error/message "Missing Keycloak client id. Set via KALEIDOSCOPE_AUTH_CLIENT environment variable."}]]
                   [:client-secret     [:string {:error/message "Missing Keycloak secret. Set via KALEIDOSCOPE_AUTH_SECRET environment variable."}]]
                   [:ssl-required      [:string {:error/message "Missing Keycloak ssl requirement. Set in code. Should never happen."}]]
                   [:confidential-port [:int {:error/message "Missing Keycloak confidential port. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:realm             (get env "KALEIDOSCOPE_AUTH_REALM")
   :auth-server-url   (get env "KALEIDOSCOPE_AUTH_URL")
   :client-id         (get env "KALEIDOSCOPE_AUTH_CLIENT")
   :client-secret     (get env "KALEIDOSCOPE_AUTH_SECRET")
   :ssl-required      "external"
   :confidential-port 0})

(defn env->pg-conn
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:dbname   [:string {:error/message "Missing DB name. Set via KALEIDOSCOPE_DB_NAME environment variable."}]]
                   [:db-port  [:string {:error/message "Missing DB port. Set via KALEIDOSCOPE_DB_PORT environment variable."}]]
                   [:host     [:string {:error/message "Missing DB host. Set via KALEIDOSCOPE_DB_HOST environment variable."}]]
                   [:username [:string {:error/message "Missing DB user. Set via KALEIDOSCOPE_DB_USER environment variable."}]]
                   [:password [:string {:error/message "Missing DB pass. Set via KALEIDOSCOPE_DB_PASSWORD environment variable."}]]
                   [:dbtype   [:string {:error/message "Missing DB type. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:dbname   (get env "KALEIDOSCOPE_DB_NAME")
   :db-port  (get env "KALEIDOSCOPE_DB_PORT" "5432")
   :host     (get env "KALEIDOSCOPE_DB_HOST")
   :username (get env "KALEIDOSCOPE_DB_USER")
   :password (get env "KALEIDOSCOPE_DB_PASSWORD")
   :dbtype   "postgresql"})

(defn env->andrewslai-s3
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:bucket [:string {:error/message "Missing S3 bucket. Set via KALEIDOSCOPE_BUCKET environment variable."}]]
                   [:creds  [:any {:error/message "Missing S3 credential provider chain. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:bucket (get env "KALEIDOSCOPE_BUCKET")
   :creds  s3-storage/CustomAWSCredentialsProviderChain})

(defn env->caheriaguilar-s3
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:bucket [:string {:error/message "Missing S3 bucket. Set via CAHERIAGUILAR_BUCKET environment variable."}]]
                   [:creds  [:any {:error/message "Missing S3 credential provider chain. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:bucket (get env "CAHERIAGUILAR_BUCKET" "caheriaguilar")
   :creds  s3-storage/CustomAWSCredentialsProviderChain})

(defn env->sahiltalkingcents-s3
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:bucket [:string {:error/message "Missing S3 bucket. Set via SAHILTALKINGCENTS_BUCKET environment variable."}]]
                   [:creds  [:any {:error/message "Missing S3 credential provider chain. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:bucket (get env "SAHILTALKINGCENTS_BUCKET" "sahiltalkingcents")
   :creds  s3-storage/CustomAWSCredentialsProviderChain})

(defn env->wedding-s3
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:bucket [:string {:error/message "Missing Wedding S3 bucket. Set via KALEIDOSCOPE_WEDDING_BUCKET environment variable."}]]
                   [:creds  [:any {:error/message "Missing Wedding S3 credential provider chain. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:bucket (get env "KALEIDOSCOPE_WEDDING_BUCKET")
   :creds  s3-storage/CustomAWSCredentialsProviderChain})

(defn env->andrewslai-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via KALEIDOSCOPE_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "KALEIDOSCOPE_STATIC_CONTENT_FOLDER")})

(defn env->caheriaguilar-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via CAHERIAGUILAR_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "CAHERIAGUILAR_STATIC_CONTENT_FOLDER")})

(defn env->sahiltalkingcents-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via SAHILTALKINGCENTS_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "SAHILTALKINGCENTS_STATIC_CONTENT_FOLDER")})

(defn env->wedding-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via KALEIDOSCOPE_WEDDING_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_FOLDER")})




;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration map
;; Parse environment variables into a map of config values:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;


(defn initialize-connection-pool!
  "this code initializes the pool and performs a validation check:
  It prevents us from having the first db request need to initialize the pool"
  [^HikariDataSource ds]
  (log/info "Initializing connection pool!")
  (.close (next/get-connection ds))
  (log/info "Connection pool initialized!"))

(def database-boot-instructions
  {:name      :database-connection
   :path      "KALEIDOSCOPE_DB_TYPE"
   :launchers {"postgres"          (fn  [env]
                                     (let [ds (connection/->pool HikariDataSource
                                                                 (env->pg-conn env))]
                                       (initialize-connection-pool! ds)
                                       ds))
               "embedded-h2"       (fn [_env] (embedded-h2/fresh-db!))
               "embedded-postgres" (fn [_env] (embedded-pg/fresh-db!))}
   :default   "postgres"})

(def andrewslai-authentication-boot-instructions
  {:name      :andrewslai-authentication
   :path      "KALEIDOSCOPE_AUTH_TYPE"
   :launchers {"keycloak"                  (fn  [env] (bb/keycloak-backend (env->keycloak env)))
               "always-unauthenticated"    (fn [_env] bb/unauthenticated-backend)
               "custom-authenticated-user" (fn [_env] (bb/authenticated-backend {:name "Test User"
                                                                                 :sub  "my-user-id"
                                                                                 :realm_access {:roles ["andrewslai" "wedding"]}}))}
   :default   "keycloak"})

(def andrewslai-authorization-boot-instructions
  {:name      :andrewslai-authorization
   :path      "KALEIDOSCOPE_AUTHORIZATION_TYPE"
   :launchers {"public-access"           (fn [_env] tu/public-access)
               "use-access-control-list" (fn [_env] andrewslai/ANDREWSLAI-ACCESS-CONTROL-LIST)}
   :default   "use-access-control-list"})

(def andrewslai-static-content-adapter-boot-instructions
  {:name      :andrewslai-static-content-adapter
   :path      "KALEIDOSCOPE_STATIC_CONTENT_TYPE"
   :launchers {"none"             (fn [_env] identity)
               "s3"               (fn  [env] (s3-storage/map->S3 (env->andrewslai-s3 env)))
               "in-memory"        (fn [_env] (memory/map->MemFS {:store (atom memory/example-fs)}))
               "local-filesystem" (fn  [env] (local-fs/map->LocalFS (env->andrewslai-local-fs env)))}
   :default   "s3"})

(def caheriaguilar-authentication-boot-instructions
  {:name      :caheriaguilar-authentication
   :path      "CAHERIAGUILAR_AUTH_TYPE"
   :launchers {"keycloak"                  (fn  [env] (bb/keycloak-backend (env->keycloak env)))
               "always-unauthenticated"    (fn [_env] bb/unauthenticated-backend)
               "custom-authenticated-user" (fn [_env] (bb/authenticated-backend {:name         "Test User"
                                                                                 :sub          "my-user-id"
                                                                                 :realm_access {:roles ["andrewslai" "wedding" "caheriaguilar"]}}))}
   :default   "keycloak"})

(def caheriaguilar-authorization-boot-instructions
  {:name      :caheriaguilar-authorization
   :path      "CAHERIAGUILAR_AUTHORIZATION_TYPE"
   :launchers {"public-access"           (fn [_env] tu/public-access)
               "use-access-control-list" (fn [_env] caheriaguilar/CAHERIAGUILAR-ACCESS-CONTROL-LIST)}
   :default   "use-access-control-list"})

(def caheriaguilar-static-content-adapter-boot-instructions
  {:name      :caheriaguilar-static-content-adapter
   :path      "CAHERIAGUILAR_STATIC_CONTENT_TYPE"
   :launchers {"none"             (fn [_env] identity)
               "s3"               (fn  [env] (s3-storage/map->S3 (env->caheriaguilar-s3 env)))
               "in-memory"        (fn [_env] (memory/map->MemFS {:store (atom memory/example-fs)}))
               "local-filesystem" (fn  [env] (local-fs/map->LocalFS (env->caheriaguilar-local-fs env)))}
   :default   "s3"})

(def sahiltalkingcents-authentication-boot-instructions
  {:name      :sahiltalkingcents-authentication
   :path      "SAHILTALKINGCENTS_AUTH_TYPE"
   :launchers {"keycloak"                  (fn  [env] (bb/keycloak-backend (env->keycloak env)))
               "always-unauthenticated"    (fn [_env] bb/unauthenticated-backend)
               "custom-authenticated-user" (fn [_env] (bb/authenticated-backend {:name         "Test User"
                                                                                 :sub          "my-user-id"
                                                                                 :realm_access {:roles ["andrewslai" "wedding" "sahiltalkingcents"]}}))}
   :default   "keycloak"})

(def sahiltalkingcents-authorization-boot-instructions
  {:name      :sahiltalkingcents-authorization
   :path      "SAHILTALKINGCENTS_AUTHORIZATION_TYPE"
   :launchers {"public-access"           (fn [_env] tu/public-access)
               "use-access-control-list" (fn [_env] sahiltalkingcents/SAHILTALKINGCENTS-ACCESS-CONTROL-LIST)}
   :default   "use-access-control-list"})

(def sahiltalkingcents-static-content-adapter-boot-instructions
  {:name      :sahiltalkingcents-static-content-adapter
   :path      "SAHILTALKINGCENTS_STATIC_CONTENT_TYPE"
   :launchers {"none"             (fn [_env] identity)
               "s3"               (fn  [env] (s3-storage/map->S3 (env->sahiltalkingcents-s3 env)))
               "in-memory"        (fn [_env] (memory/map->MemFS {:store (atom memory/example-fs)}))
               "local-filesystem" (fn  [env] (local-fs/map->LocalFS (env->sahiltalkingcents-local-fs env)))}
   :default   "s3"})

(def wedding-authentication-boot-instructions
  {:name      :wedding-authentication
   :path      "KALEIDOSCOPE_WEDDING_AUTH_TYPE"
   :launchers {"keycloak"                  (fn  [env] (bb/keycloak-backend (env->keycloak env)))
               "always-unauthenticated"    (fn [_env] bb/unauthenticated-backend)
               "custom-authenticated-user" (fn [_env] (bb/authenticated-backend {:name "Test User"
                                                                                 :realm_access {:roles ["andrewslai" "wedding"]}}))}
   :default   "keycloak"})

(def wedding-authorization-boot-instructions
  {:name      :wedding-authorization
   :path      "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"
   :launchers {"public-access"           (fn [_env] tu/public-access)
               "use-access-control-list" (fn [_env] wedding/WEDDING-ACCESS-CONTROL-LIST)}
   :default   "use-access-control-list"})

(def wedding-static-content-adapter-boot-instructions
  {:name      :wedding-static-content-adapter
   :path      "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE"
   :launchers {"none"             (fn [_env] identity)
               "s3"               (fn  [env] (s3-storage/map->S3 (env->wedding-s3 env)))
               "in-memory"        (fn [_env] (memory/map->MemFS {:store (atom memory/example-fs)}))
               "local-filesystem" (fn  [env] (local-fs/map->LocalFS (env->wedding-local-fs env)))}
   :default   "s3"})

(def DEFAULT-BOOT-INSTRUCTIONS
  "Instructions for how to boot the entire system"
  [database-boot-instructions

   andrewslai-authentication-boot-instructions
   andrewslai-authorization-boot-instructions
   andrewslai-static-content-adapter-boot-instructions

   caheriaguilar-authentication-boot-instructions
   caheriaguilar-authorization-boot-instructions
   caheriaguilar-static-content-adapter-boot-instructions

   sahiltalkingcents-authentication-boot-instructions
   sahiltalkingcents-authorization-boot-instructions
   sahiltalkingcents-static-content-adapter-boot-instructions

   wedding-authentication-boot-instructions
   wedding-authorization-boot-instructions
   wedding-static-content-adapter-boot-instructions])

(def ANDREWSLAI-BOOT-INSTRUCTIONS
  "Instructions for how to boot the Andrewslai app"
  [database-boot-instructions

   andrewslai-authentication-boot-instructions
   andrewslai-authorization-boot-instructions
   andrewslai-static-content-adapter-boot-instructions])

(def WEDDING-BOOT-INSTRUCTIONS
  "Instructions for how to boot the Andrewslai app"
  [database-boot-instructions

   wedding-authentication-boot-instructions
   wedding-authorization-boot-instructions
   wedding-static-content-adapter-boot-instructions])

(def BootInstruction
  [:map
   [:name      [:keyword {:error/message "Invalid Boot Instruction. Missing name."}]]
   [:path      [:string  {:error/message "Invalid Boot Instruction. Missing path."}]]
   [:default   [:string  {:error/message "Invalid Boot Instruction. Missing default."}]]
   [:launchers [:map     {:error/message "Invalid Boot Instruction. Missing launchers."}]]
   ])

(def BootInstructions
  [:sequential BootInstruction])

;; TODO: TEST ME!
;; TODO: 2023-01-22: Just refactored boot instructions. Need to update tests!
(defn start-system!
  {:malli/schema [:function
                  [:=> [:cat :map] :map]
                  [:=> [:cat BootInstructions :map] :map]]}
  ([env]
   (start-system! DEFAULT-BOOT-INSTRUCTIONS
                  env))
  ([boot-instructions env]
   (reduce (fn [acc {:keys [name path default launchers] :as system-component}]
             (let [launcher (get env path default)
                   init-fn  (get launchers launcher)]
               (if init-fn
                 (do (log/debugf "Starting %s using `%s`launcher: %s" name launcher init-fn)
                     (assoc acc name (init-fn env)))
                 (throw (ex-info (format "%s had invalid value [%s] for component [%s]. Valid options are: %s"
                                         path
                                         launcher
                                         name
                                         (keys launchers))
                                 {})))))
           {}
           boot-instructions)))

(defn make-middleware
  [authentication authorization]
  (comp mw/standard-stack
        (mw/auth-stack authentication authorization)))

(defn prepare-andrewslai
  [{:keys [database-connection
           andrewslai-authentication
           andrewslai-authorization
           andrewslai-static-content-adapter]
    :as system}]
  {:database               database-connection
   :http-mw                (make-middleware andrewslai-authentication
                                            andrewslai-authorization)
   :static-content-adapter andrewslai-static-content-adapter})

(defn prepare-caheriaguilar
  [{:keys [database-connection
           caheriaguilar-authentication
           caheriaguilar-authorization
           caheriaguilar-static-content-adapter]
    :as system}]
  {:database               database-connection
   :http-mw                (make-middleware caheriaguilar-authentication
                                            caheriaguilar-authorization)
   :static-content-adapter caheriaguilar-static-content-adapter})

(defn prepare-sahiltalkingcents
  [{:keys [database-connection
           sahiltalkingcents-authentication
           sahiltalkingcents-authorization
           sahiltalkingcents-static-content-adapter]
    :as system}]
  {:database               database-connection
   :http-mw                (make-middleware sahiltalkingcents-authentication
                                            sahiltalkingcents-authorization)
   :static-content-adapter sahiltalkingcents-static-content-adapter})

(defn prepare-wedding
  [{:keys [database-connection
           wedding-authentication
           wedding-authorization
           wedding-static-content-adapter]
    :as system}]
  {:database               database-connection
   :http-mw                (make-middleware wedding-authentication
                                            wedding-authorization)
   :static-content-adapter wedding-static-content-adapter})

(defn prepare-for-virtual-hosting
  [system]
  {:andrewslai        (prepare-andrewslai system)
   :wedding           (prepare-wedding system)
   :caheriaguilar     (prepare-caheriaguilar system)
   :sahiltalkingcents (prepare-sahiltalkingcents system)
   })

;; Refactor so we only have one blogging app: kaleidoscope.
(defn make-http-handler
  [{:keys [andrewslai caheriaguilar sahiltalkingcents wedding] :as components}]
  (vh/host-based-routing
   {#"caheriaguilar.and.andrewslai.com" {:priority 0
                                         :app      (wedding/wedding-app wedding)}
    #"caheriaguilar.com"                {:priority 0
                                         :app      (caheriaguilar/caheriaguilar-app caheriaguilar)}
    #"sahiltalkingcents.com"            {:priority 0
                                         :app      (sahiltalkingcents/sahiltalkingcents-app sahiltalkingcents)}
    #".*"                               {:priority 100
                                         :app      (andrewslai/andrewslai-app andrewslai)}}))

;; Updates the Malli schema validation output to only show the errors
;; This will:
;; (1) Make sure we don't log secrets
;; (2) Focus on errors so the user gets direct feedback about what to fix
(defmethod v/-format ::m/invalid-output [_ _ {:keys [value args output fn-name]} printer]
  {:body
   [:group
    (pretty/-block "Invalid function return value. Function Var:" (v/-visit fn-name printer) printer) :break :break
    (pretty/-block "Errors:" (pretty/-explain output value printer) printer) :break :break]})

(mi/collect! {:ns 'kaleidoscope.clj.init.env})
(mi/instrument! {:report (pretty/thrower)})