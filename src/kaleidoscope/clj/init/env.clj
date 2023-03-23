(ns kaleidoscope.clj.init.env
  "Parses environment variables into Clojure maps that are used to boot system
  components."
  (:require [kaleidoscope.clj.http-api.auth.buddy-backends :as bb]
            [kaleidoscope.clj.http-api.kaleidoscope :as kaleidoscope]
            [kaleidoscope.clj.http-api.middleware :as mw]
            [kaleidoscope.clj.http-api.virtual-hosting :as vh]
            [kaleidoscope.clj.persistence.filesystem.in-memory-impl :as memory]
            [kaleidoscope.clj.persistence.filesystem.local :as local-fs]
            [kaleidoscope.clj.persistence.filesystem.s3-impl :as s3-storage]
            [kaleidoscope.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.clj.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
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

(def domains
  #{"andrewslai.com"
    "sahiltalkingcents.com"
    "caheriaguilar.com"
    "caheriaguilar.and.andrewslai.com"})

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

(defn env->kaleidoscope-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via KALEIDOSCOPE_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "KALEIDOSCOPE_STATIC_CONTENT_FOLDER")})



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


(def kaleidoscope-authentication-boot-instructions
  {:name      :kaleidoscope-authentication
   :path      "KALEIDOSCOPE_AUTH_TYPE"
   :launchers {"keycloak"                  (fn  [env] (bb/keycloak-backend (env->keycloak env)))
               "always-unauthenticated"    (fn [_env] bb/unauthenticated-backend)
               "custom-authenticated-user" (fn [_env] (bb/authenticated-backend {:name         "Test User"
                                                                                 :sub          "my-user-id"
                                                                                 :realm_access {:roles ["andrewslai.com:admin"
                                                                                                        "sahiltalkingcents.com:admin"
                                                                                                        "caheriaguilar.com:admin"
                                                                                                        "caheriaguilar.and.andrewslai.com:admin"]}}))}
   :default   "keycloak"})

(def kaleidoscope-authorization-boot-instructions
  {:name      :kaleidoscope-authorization
   :path      "KALEIDOSCOPE_AUTHORIZATION_TYPE"
   :launchers {"public-access"           (fn [_env] tu/public-access)
               "use-access-control-list" (fn [_env] kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST)}
   :default   "use-access-control-list"})

(def kaleidoscope-static-content-adapter-boot-instructions
  {:name      :kaleidoscope-static-content-adapters
   :path      "KALEIDOSCOPE_STATIC_CONTENT_TYPE"
   :launchers {"none"             (fn [_env] identity)
               "s3"               (fn  [env] {"andrewslai"                   (s3-storage/map->S3 {:bucket "andrewslai"
                                                                                                  :creds  s3-storage/CustomAWSCredentialsProviderChain})
                                              "caheriaguilar"                (s3-storage/map->S3 {:bucket "caheriaguilar"
                                                                                                  :creds  s3-storage/CustomAWSCredentialsProviderChain})
                                              "sahiltalkingcents"            (s3-storage/map->S3 {:bucket "sahiltalkingcents"
                                                                                                  :creds  s3-storage/CustomAWSCredentialsProviderChain})
                                              "caheriaguilar.and.andrewslai" (s3-storage/map->S3 {:bucket "wedding"
                                                                                                  :creds  s3-storage/CustomAWSCredentialsProviderChain})})
               "in-memory"        (fn [_env] {"andrewslai"                   (memory/map->MemFS {:store (atom memory/example-fs)})
                                              "caheriaguilar"                (memory/map->MemFS {:store (atom memory/example-fs)})
                                              "sahiltalkingcents"            (memory/map->MemFS {:store (atom memory/example-fs)})
                                              "caheriaguilar.and.andrewslai" (memory/map->MemFS {:store (atom memory/example-fs)})})
               "local-filesystem" (fn  [env] {"andrewslai"                   (local-fs/map->LocalFS (env->kaleidoscope-local-fs env))
                                              "caheriaguilar"                (local-fs/map->LocalFS (env->kaleidoscope-local-fs env))
                                              "sahiltalkingcents"            (local-fs/map->LocalFS (env->kaleidoscope-local-fs env))
                                              "caheriaguilar.and.andrewslai" (local-fs/map->LocalFS (env->kaleidoscope-local-fs env))})}
   :default   "s3"})

(def DEFAULT-BOOT-INSTRUCTIONS
  "Instructions for how to boot the entire system"
  [database-boot-instructions

   kaleidoscope-authentication-boot-instructions
   kaleidoscope-authorization-boot-instructions
   kaleidoscope-static-content-adapter-boot-instructions])

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

(defn prepare-kaleidoscope
  [{:keys [database-connection
           kaleidoscope-authentication
           kaleidoscope-authorization
           kaleidoscope-static-content-adapters]
    :as   system}]
  {:database                database-connection
   :http-mw                 (make-middleware kaleidoscope-authentication
                                             kaleidoscope-authorization)
   :static-content-adapters kaleidoscope-static-content-adapters})

(defn prepare-for-virtual-hosting
  [system]
  {:kaleidoscope (prepare-kaleidoscope system)})

(defn make-http-handler
  [{:keys [kaleidoscope] :as components}]
  (vh/host-based-routing {#".*" {:priority 100
                                 :app      (kaleidoscope/kaleidoscope-app kaleidoscope)}}))

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
