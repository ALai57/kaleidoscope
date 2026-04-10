(ns kaleidoscope.init.env
  "Parses environment variables into Clojure maps that are used to boot system
  components."
  (:require [cognitect.aws.client.api :as aws]
            [kaleidoscope.clients.bugsnag :as bugsnag]
            [kaleidoscope.clients.error-reporter :as er]
            [kaleidoscope.clients.session-tracker :as st]
            [kaleidoscope.http-api.auth.buddy-backends :as bb]
            [kaleidoscope.http-api.kaleidoscope :as kaleidoscope]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.http-api.virtual-hosting :as vh]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as memory]
            [kaleidoscope.persistence.filesystem.local :as local-fs]
            [kaleidoscope.persistence.filesystem.s3-impl :as s3-storage]
            [kaleidoscope.http-api.auth.access-control :as ac]
            [kaleidoscope.utils.versioning :as vu]
            [malli.core :as m]
            [malli.dev.pretty :as pretty]
            [malli.dev.virhe :as v]
            [next.jdbc :as next]
            [next.jdbc.connection :as connection]
            [reitit.middleware :as middleware]
            [taoensso.timbre :as log])
  (:import (com.zaxxer.hikari HikariDataSource)
           (io.opentelemetry.api GlobalOpenTelemetry)
           (io.opentelemetry.instrumentation.hikaricp.v3_0 HikariTelemetry)
           (io.opentelemetry.instrumentation.jdbc.datasource JdbcTelemetry)))

(def domains
  "All the domains that the kaleidoscope application serves."
  #{"andrewslai.com"
    "sahiltalkingcents.com"
    "caheriaguilar.com"
    "caheriaguilar.and.andrewslai.com"})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration maps
;; Parse environment variables into a map of config values
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn env->auth0
  [env]
  {:domain   (get env "KALEIDOSCOPE_AUTH_DOMAIN")
   :audience (get env "KALEIDOSCOPE_AUTH_AUDIENCE")})


(defn env->pg-conn
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:dbname [:string {:error/message "Missing DB name. Set via KALEIDOSCOPE_DB_NAME environment variable."}]]
                   [:db-port [:string {:error/message "Missing DB port. Set via KALEIDOSCOPE_DB_PORT environment variable."}]]
                   [:host [:string {:error/message "Missing DB host. Set via KALEIDOSCOPE_DB_HOST environment variable."}]]
                   [:username [:string {:error/message "Missing DB user. Set via KALEIDOSCOPE_DB_USER environment variable."}]]
                   [:password [:string {:error/message "Missing DB pass. Set via KALEIDOSCOPE_DB_PASSWORD environment variable."}]]
                   [:dbtype [:string {:error/message "Missing DB type. Set in code. Should never happen."}]]
                   ;; pascalCase for Java Interop
                   [:minimumIdle [:int {:error/message "Missing Minimum Idle. Set in code. Should never happen."}]]
                   [:idleTimeout [:int {:error/message "Missing DB Idle timeout. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:dbname      (get env "KALEIDOSCOPE_DB_NAME")
   :db-port     (get env "KALEIDOSCOPE_DB_PORT" "5432")
   :host        (get env "KALEIDOSCOPE_DB_HOST")
   :username    (get env "KALEIDOSCOPE_DB_USER")
   :password    (get env "KALEIDOSCOPE_DB_PASSWORD")
   :dbtype      "postgresql"
   :sslmode     (get env "KALEIDOSCOPE_DB_SSL_MODE" "require")
   ;; Minimum number of connections when the pool is idle. Set intentionally
   ;; low, because the app uses db.t4g.micro and we don't want to eat up CPU.
   :minimumIdle 3
   :idleTimeout 120000                                      ;; Shut down connections after 2 mins
   })

(defn env->kaleidoscope-image-notifier
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:image-notifier-arn [:string {:error/message "Missing Image Notifier ARN. Set via KALEIDOSCOPE_IMAGE_NOTIFIER_ARN environment variable"}]]]]
   :malli/scope  #{:output}}
  [env]
  {:image-notifier-arn (get env "KALEIDOSCOPE_IMAGE_NOTIFIER_ARN")})


(defn env->kaleidoscope-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via KALEIDOSCOPE_STATIC_CONTENT_FOLDER environment variable."}]]
                   [:subpath [:string {:error/message "Missing Local FS subpath. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env subpath]
  {:root (if subpath
           (str (get env "KALEIDOSCOPE_STATIC_CONTENT_FOLDER") "/" subpath)
           (get env "KALEIDOSCOPE_STATIC_CONTENT_FOLDER"))})

(defn env->bugsnag
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:api-key [:string {:error/message "Missing Bugsnag key. Set via KALEIDOSCOPE_BUGSNAG_KEY environment variable."}]]
                   [:app-version [:string {:error/message "Missing App version. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:api-key     (get env "KALEIDOSCOPE_BUGSNAG_KEY")
   :app-version (:version (vu/get-version-details))})

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

(defn initialize-connection-pool!
  "Eagerly initialize the DB pool: Doing this eagerly prevents us from having
  the first db request initialize the pool."
  [^HikariDataSource ds]
  (log/info "Initializing connection pool!")
  (.close (next/get-connection ds))
  (log/info "Connection pool initialized!"))

(def database-boot-instructions
  {:name      :database-connection
   :path      "KALEIDOSCOPE_DB_TYPE"
   :launchers {"postgres"          (fn [env]
                                     (let [ds   (connection/->pool HikariDataSource
                                                                   (env->pg-conn env))
                                           _    (initialize-connection-pool! ds)
                                           otel (GlobalOpenTelemetry/get)
                                           _    (.setMetricsTrackerFactory
                                                  ds (.createMetricsTrackerFactory
                                                       (HikariTelemetry/create otel)))]
                                       (.wrap (JdbcTelemetry/create otel) ds)))
               "embedded-h2"       (fn [_env]
                                     (require (symbol "kaleidoscope.persistence.rdbms.embedded-h2-impl"))
                                     ((resolve 'kaleidoscope.persistence.rdbms.embedded-h2-impl/fresh-db!)))
               "embedded-postgres" (fn [_env]
                                     (require (symbol "kaleidoscope.persistence.rdbms.embedded-postgres-impl"))
                                     ((resolve 'kaleidoscope.persistence.rdbms.embedded-postgres-impl/fresh-db!)))}
   :default   "postgres"})


(def kaleidoscope-authentication-boot-instructions
  {:name      :kaleidoscope-authentication
   :path      "KALEIDOSCOPE_AUTH_TYPE"
   :launchers {"auth0"                     (fn [env] (bb/auth0-backend (env->auth0 env)))
               "always-unauthenticated"    (fn [_env] bb/unauthenticated-backend)
               "custom-authenticated-user" (fn [_env] (bb/authenticated-backend {:name         "Test User"
                                                                                 :sub          "my-user-id"
                                                                                 :email        "test@test.com" ;; Critical for identity-based access
                                                                                 :realm_access {:roles ["andrewslai.com:admin"
                                                                                                        "andrewslai.test:admin"
                                                                                                        "andrewslai.localhost:admin"
                                                                                                        "sahiltalkingcents.com:admin"
                                                                                                        "caheriaguilar.com:admin"
                                                                                                        "caheriaguilar.and.andrewslai.com:admin"]}}))}
   :default   "auth0"})

(def kaleidoscope-authorization-boot-instructions
  {:name      :kaleidoscope-authorization
   :path      "KALEIDOSCOPE_AUTHORIZATION_TYPE"
   :launchers {"public-access"           (fn [_env] ac/public-access)
               "use-access-control-list" (fn [_env] kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST)}
   :default   "use-access-control-list"})

(def kaleidoscope-notify-image-resizer-boot-instructions
  {:name      :kaleidoscope-notify-image-resizer
   :path      "KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE"
   :launchers {"none"    (fn [_env] identity)
               "println" (fn [_env] (fn [& args] (println "Arguments to image notifier" args)))
               "sns"     (fn [env]
                           (let [{:keys [image-notifier-arn]} (env->kaleidoscope-image-notifier env)
                                 client                        (aws/client {:api :sns})]
                             (fn [& {:keys [subject message message-attributes]}]
                               (aws/invoke client
                                           {:op      :Publish
                                            :request {:TopicArn          image-notifier-arn
                                                      :Subject           subject
                                                      :Message           message
                                                      :MessageAttributes (into {}
                                                                               (map (fn [[k v]]
                                                                                      [k {:DataType    "String"
                                                                                          :StringValue v}]))
                                                                               message-attributes)}}))))}
   :default   "sns"})

;; TODO: Allow this to work using entire domain name. Otherwise, we could get collisions.
(def kaleidoscope-static-content-adapter-boot-instructions
  {:name      :kaleidoscope-static-content-adapters
   :path      "KALEIDOSCOPE_STATIC_CONTENT_TYPE"
   :launchers {"none"             (fn [_env] identity)
               "s3"               (fn [env] {"kaleidoscope.pub"                 (s3-storage/make-s3 {:bucket "kaleidoscope.pub"})
                                             "kaleidoscope.client"              (s3-storage/make-s3 {:bucket "kaleidoscope.client"})
                                             "andrewslai.com"                   (s3-storage/make-s3 {:bucket "andrewslai.com"})
                                             "caheriaguilar.com"                (s3-storage/make-s3 {:bucket "caheriaguilar.com"})
                                             "sahiltalkingcents.com"            (s3-storage/make-s3 {:bucket "sahiltalkingcents.com"})
                                             "caheriaguilar.and.andrewslai.com" (s3-storage/make-s3 {:bucket "wedding"})

                                             ;; For testing locally
                                             "andrewslai.com.localhost"         (s3-storage/make-s3 {:bucket "andrewslai.com"})
                                             })
               "in-memory"        (fn [_env] {"kaleidoscope.pub"                 (memory/make-mem-fs {:store (atom memory/example-fs)})
                                              "kaleidoscope.client"              (memory/make-mem-fs {:store (atom memory/example-fs)})
                                              "andrewslai.com"                   (memory/make-mem-fs {:store (atom memory/example-fs)})
                                              "andrewslai.test"                  (memory/make-mem-fs {:store (atom memory/example-fs)})
                                              "caheriaguilar.com"                (memory/make-mem-fs {:store (atom memory/example-fs)})
                                              "sahiltalkingcents.com"            (memory/make-mem-fs {:store (atom memory/example-fs)})
                                              "caheriaguilar.and.andrewslai.com" (memory/make-mem-fs {:store (atom memory/example-fs)})})
               "local-filesystem" (fn [env] {"kaleidoscope.pub"                 (local-fs/make-local-fs (env->kaleidoscope-local-fs env "kaleidoscope.pub"))
                                             "kaleidoscope.pub.localhost"       (local-fs/make-local-fs (env->kaleidoscope-local-fs env "kaleidoscope.pub"))
                                             "kaleidoscope.client"              (local-fs/make-local-fs (env->kaleidoscope-local-fs env "kaleidoscope.client"))
                                             "kaleidoscope.client.localhost"    (local-fs/make-local-fs (env->kaleidoscope-local-fs env "kaleidoscope.client"))
                                             "andrewslai.com"                   (local-fs/make-local-fs (env->kaleidoscope-local-fs env "andrewslai.com"))
                                             "andrewslai.com.localhost"         (local-fs/make-local-fs (env->kaleidoscope-local-fs env "andrewslai.com"))
                                             "caheriaguilar.com"                (local-fs/make-local-fs (env->kaleidoscope-local-fs env "caheriaguilar.com"))
                                             "sahiltalkingcents.com"            (local-fs/make-local-fs (env->kaleidoscope-local-fs env "sahiltalkingcents.com"))
                                             "caheriaguilar.and.andrewslai.com" (local-fs/make-local-fs (env->kaleidoscope-local-fs env "caheriaguilar.and.andrewslai.com"))})}
   :default   "s3"})

(def exception-reporter-boot-instructions
  {:name      :exception-reporter
   :path      "KALEIDOSCOPE_EXCEPTION_REPORTER_TYPE"
   :launchers {"bugsnag" (fn [env] (bugsnag/make-bugsnag-client (env->bugsnag env)))
               "none"    (fn [_env] (reify
                                      er/ErrorReporter
                                      (report! [this e]
                                        (log/error "Notifying! Caught exception" e))

                                      st/SessionTracker
                                      (start! [this]
                                        (log/debug "Starting session with mock session tracker..."))))}
   :default   "none"})

(def DEFAULT-BOOT-INSTRUCTIONS
  "Instructions for how to boot the entire system"
  [database-boot-instructions
   exception-reporter-boot-instructions

   kaleidoscope-authentication-boot-instructions
   kaleidoscope-authorization-boot-instructions
   kaleidoscope-static-content-adapter-boot-instructions
   kaleidoscope-notify-image-resizer-boot-instructions])

(def BootInstruction
  [:map
   [:name [:keyword {:error/message "Invalid Boot Instruction. Missing name."}]]
   [:path [:string {:error/message "Invalid Boot Instruction. Missing path."}]]
   [:default [:string {:error/message "Invalid Boot Instruction. Missing default."}]]
   [:launchers [:map {:error/message "Invalid Boot Instruction. Missing launchers."}]]
   ])

(def BootInstructions
  [:sequential BootInstruction])

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
                   init-fn (get launchers launcher)]
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

(defn prepare-kaleidoscope
  [{:keys [database-connection
           exception-reporter
           kaleidoscope-authentication
           kaleidoscope-authorization
           kaleidoscope-static-content-adapters
           kaleidoscope-notify-image-resizer]
    :as   system}]
  {:database                database-connection
   :exception-reporter      (partial er/report! exception-reporter)
   :session-tracking        {:name ::session-tracking
                             :wrap (mw/session-tracking-stack exception-reporter)}
   :auth-stack              {:name ::auth-stack
                             :wrap (apply comp (mw/auth-stack kaleidoscope-authentication
                                                              kaleidoscope-authorization))}
   :static-content-adapters kaleidoscope-static-content-adapters
   :notify-image-resizer!   kaleidoscope-notify-image-resizer})

(defn make-http-handler
  [system]
  (let [apps {:kaleidoscope (prepare-kaleidoscope system)}]
    (vh/host-based-routing {#".*" {:priority 100
                                   :app      (kaleidoscope/kaleidoscope-app (:kaleidoscope apps))}})))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Validate environment
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Updates the Malli schema validation output to only show the errors
;; This will:
;; (1) Make sure we don't log secrets
;; (2) Focus on errors so the user gets direct feedback about what to fix
(defmethod v/-format ::m/invalid-output [_ _ {:keys [value args output fn-name]} printer]
  {:body
   [:group
    (pretty/-block "Invalid function return value. Function Var:" (v/-visit fn-name printer) printer) :break :break
    (pretty/-block "Errors:" (pretty/-explain output value printer) printer) :break :break]})
