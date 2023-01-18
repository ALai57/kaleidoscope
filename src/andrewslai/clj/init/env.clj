(ns andrewslai.clj.init.env
  "Parses environment variables into Clojure maps that are used to boot system
  components."
  (:require [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.persistence.filesystem.in-memory-impl :as memory]
            [andrewslai.clj.persistence.filesystem.local :as local-fs]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [andrewslai.clj.test-utils :as tu]
            [malli.core :as m]
            [malli.error :as me]
            [malli.dev.pretty :as pretty]
            [malli.dev.virhe :as v]
            [malli.instrument :as mi]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Launch Options Map
;; Parse environment variables into a map of `launch-options`:
;; the minimal amount of information needed to launch a webserver.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def LaunchOptionsMap
  "Describes what system components should be started at app startup time."
  [:map
   [:port  [:int {:error/message "Invalid port. Set via ANDREWSLAI_PORT environment variable."}]]
   [:level [:enum {:error/message "Invalid log level. Set via ANDREWSLAI_LOG_LEVEL environment variable."} :trace :debug :info :warn :error :fatal]]

   [:database
    [:map
     [:db-type [:enum {:error/message "Invalid database type. Set via ANDREWSLAI_DB_TYPE environment variable."} :postgres :embedded-h2 :embedded-postgres]]]]

   [:andrewslai
    [:map
     [:authentication-type [:enum {:error/message "Invalid authentication type. Set via ANDREWSLAI_AUTH_TYPE environment variable."} :keycloak :always-unauthenticated :custom-authenticated-user]]
     [:authorization-type [:enum {:error/message "Invalid authorization type. Set via ANDREWSLAI_AUTHORIZATION_TYPE environment variable."} :use-access-control-list :public-access]]
     [:static-content-type [:enum {:error/message "Invalid static content type. Set via ANDREWSLAI_STATIC_CONTENT_TYPE environment variable."} :none :s3 :in-memory :local-filesystem]]]]

   [:wedding
    [:map
     [:authentication-type [:enum {:error/message "Invalid authentication type. Set via ANDREWSLAI_AUTH_TYPE environment variable."} :keycloak :always-unauthenticated :custom-authenticated-user]]
     [:authorization-type [:enum {:error/message "Invalid authorization type. Set via ANDREWSLAI_AUTHORIZATION_TYPE environment variable."} :use-access-control-list :public-access]]
     [:static-content-type [:enum {:error/message "Invalid static content type. Set via ANDREWSLAI_STATIC_CONTENT_TYPE environment variable."} :none :s3 :in-memory :local-filesystem]]]]
   ])

(defn environment->launch-options
  "Reads the environment to determine what system components should be started at app startup time."
  {:malli/schema [:=> [:cat :map] LaunchOptionsMap]
   :malli/scope  #{:output}}
  [env]
  (let [kenv (fn [env-var default] (keyword (get env env-var default)))
        ienv (fn [env-var default] (Integer/parseInt (get env env-var (str default))))]

    {:port  (ienv "ANDREWSLAI_PORT"      5000)
     :level (kenv "ANDREWSLAI_LOG_LEVEL" :info)

     :database   {:db-type             (kenv "ANDREWSLAI_DB_TYPE"                     :postgres)}
     :andrewslai {:authentication-type (kenv "ANDREWSLAI_AUTH_TYPE"                   :keycloak)
                  :authorization-type  (kenv "ANDREWSLAI_AUTHORIZATION_TYPE"          :use-access-control-list)
                  :static-content-type (kenv "ANDREWSLAI_STATIC_CONTENT_TYPE"         :none)}
     :wedding    {:authentication-type (kenv "ANDREWSLAI_WEDDING_AUTH_TYPE"           :keycloak)
                  :authorization-type  (kenv "ANDREWSLAI_WEDDING_AUTHORIZATION_TYPE"  :use-access-control-list)
                  :static-content-type (kenv "ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" :none)}}))

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
                   [:realm             [:string {:error/message "Missing Keycloak realm. Set via ANDREWSLAI_AUTH_REALM environment variable."}]]
                   [:auth-server-url   [:string {:error/message "Missing Keycloak auth server url. Set via ANDREWSLAI_AUTH_URL environment variable."}]]
                   [:client-id         [:string {:error/message "Missing Keycloak client id. Set via ANDREWSLAI_AUTH_CLIENT environment variable."}]]
                   [:client-secret     [:string {:error/message "Missing Keycloak secret. Set via ANDREWSLAI_AUTH_SECRET environment variable."}]]
                   [:ssl-required      [:string {:error/message "Missing Keycloak ssl requirement. Set in code. Should never happen."}]]
                   [:confidential-port [:string {:error/message "Missing Keycloak confidential port. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:realm             (get env "ANDREWSLAI_AUTH_REALM")
   :auth-server-url   (get env "ANDREWSLAI_AUTH_URL")
   :client-id         (get env "ANDREWSLAI_AUTH_CLIENT")
   :client-secret     (get env "ANDREWSLAI_AUTH_SECRET")
   :ssl-required      "external"
   :confidential-port 0})

(defn env->pg-conn
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:dbname   [:string {:error/message "Missing DB name. Set via ANDREWSLAI_DB_NAME environment variable."}]]
                   [:db-port  [:string {:error/message "Missing DB port. Set via ANDREWSLAI_DB_PORT environment variable."}]]
                   [:host     [:string {:error/message "Missing DB host. Set via ANDREWSLAI_DB_HOST environment variable."}]]
                   [:user     [:string {:error/message "Missing DB user. Set via ANDREWSLAI_DB_USER environment variable."}]]
                   [:password [:string {:error/message "Missing DB pass. Set via ANDREWSLAI_DB_PASSWORD environment variable."}]]
                   [:dbtype   [:string {:error/message "Missing DB type. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:dbname   (get env "ANDREWSLAI_DB_NAME")
   :db-port  (get env "ANDREWSLAI_DB_PORT" "5432")
   :host     (get env "ANDREWSLAI_DB_HOST")
   :user     (get env "ANDREWSLAI_DB_USER")
   :password (get env "ANDREWSLAI_DB_PASSWORD")
   :dbtype   "postgresql"})

(defn env->andrewslai-s3
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:bucket [:string {:error/message "Missing S3 bucket. Set via ANDREWSLAI_BUCKET environment variable."}]]
                   [:creds  [:string {:error/message "Missing S3 credential provider chain. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:bucket (get "ANDREWSLAI_BUCKET")
   :creds  s3-storage/CustomAWSCredentialsProviderChain})

(defn env->wedding-s3
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:bucket [:string {:error/message "Missing Wedding S3 bucket. Set via ANDREWSLAI_WEDDING_BUCKET environment variable."}]]
                   [:creds  [:string {:error/message "Missing Wedding S3 credential provider chain. Set in code. Should never happen."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:bucket (get "ANDREWSLAI_WEDDING_BUCKET")
   :creds  s3-storage/CustomAWSCredentialsProviderChain})

(defn env->andrewslai-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via ANDREWSLAI_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "ANDREWSLAI_STATIC_CONTENT_FOLDER")})

(defn env->wedding-local-fs
  {:malli/schema [:=> [:cat :map]
                  [:map
                   [:root [:string {:error/message "Missing Local FS root path. Set via ANDREWSLAI_WEDDING_STATIC_CONTENT_FOLDER environment variable."}]]]]
   :malli/scope  #{:output}}
  [env]
  {:root (get env "ANDREWSLAI_WEDDING_STATIC_CONTENT_FOLDER")})



(def init-andrewslai-keycloak             (fn [env] (bb/keycloak-backend (env->keycloak env))))
(def init-andrewslai-authenticated-user   (fn [_env] (bb/authenticated-backend)))
(def init-andrewslai-unauthenticated-user (fn [_env] bb/unauthenticated-backend))
(def init-andrewslai-public-access        (fn [_env] tu/public-access))
(def init-andrewslai-access-control       (fn [_env] andrewslai/ANDREWSLAI-ACCESS-CONTROL-LIST))
(def init-andrewslai-s3-filesystem        (fn [env] (s3-storage/map->S3 (env->andrewslai-s3 env))))
(def init-andrewslai-in-memory-filesystem (fn [_env] (memory/map->MemFS {:store (atom memory/example-fs)})))
(def init-andrewslai-local-filesystem     (fn [env] (local-fs/map->LocalFS (env->andrewslai-local-fs env))))

(def init-wedding-keycloak             (fn [env] (bb/keycloak-backend (env->keycloak env))))
(def init-wedding-authenticated-user   (fn [_env] (bb/authenticated-backend)))
(def init-wedding-unauthenticated-user (fn [_env] bb/unauthenticated-backend))
(def init-wedding-public-access        (fn [_env] tu/public-access))
(def init-wedding-access-control       (fn [_env] wedding/WEDDING-ACCESS-CONTROL-LIST))
(def init-wedding-s3-filesystem        (fn [env] (s3-storage/map->S3 (env->wedding-s3 env))))
(def init-wedding-in-memory-filesystem (fn [_env] (memory/map->MemFS {:store (atom memory/example-fs)})))
(def init-wedding-local-filesystem     (fn [env] (local-fs/map->LocalFS (env->andrewslai-local-fs env))))

(def init-postgres-connection          (fn [env] (next/get-datasource (env->pg-conn env))))
(def init-embedded-postgres-connection (fn [_env] (embedded-pg/fresh-db!)))
(def init-embedded-h2-connection       (fn [_env] (embedded-h2/fresh-db!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration map
;; Parse environment variables into a map of config values:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def database-boot-instructions
  {:name      :database-connection
   :path      [:database :db-type]
   :launchers {:postgres          init-postgres-connection
               :embedded-h2       init-embedded-h2-connection
               :embedded-postgres init-embedded-postgres-connection}})

(def andrewslai-authentication-boot-instructions
  {:name      :andrewslai-authentication
   :path      [:andrewslai :authentication-type]
   :launchers {:keycloak                  init-andrewslai-keycloak
               :always-unauthenticated    init-andrewslai-unauthenticated-user
               :custom-authenticated-user init-andrewslai-authenticated-user}})

(def andrewslai-authorization-boot-instructions
  {:name      :andrewslai-authorization
   :path      [:andrewslai :authorization-type]
   :launchers {:public-access           init-andrewslai-public-access
               :use-access-control-list init-andrewslai-access-control}})

(def andrewslai-static-content-adapter-boot-instructions
  {:name      :andrewslai-static-content-adapter
   :path      [:andrewslai :static-content-type]
   :launchers {:none             identity
               :s3               init-andrewslai-s3-filesystem
               :in-memory        init-andrewslai-in-memory-filesystem
               :local-filesystem init-andrewslai-local-filesystem}})

(def wedding-authentication-boot-instructions
  {:name      :wedding-authentication
   :path      [:wedding :authentication-type]
   :launchers {:keycloak                  init-wedding-keycloak
               :always-unauthenticated    init-wedding-unauthenticated-user
               :custom-authenticated-user init-wedding-authenticated-user}})

(def wedding-authorization-boot-instructions
  {:name      :wedding-authorization
   :path      [:wedding :authorization-type]
   :launchers {:public-access           init-wedding-public-access
               :use-access-control-list init-wedding-access-control}})

(def wedding-static-content-adapter-boot-instructions
  {:name      :wedding-static-content-adapter
   :path      [:wedding :static-content-type]
   :launchers {:none             identity
               :s3               init-wedding-s3-filesystem
               :in-memory        init-wedding-in-memory-filesystem
               :local-filesystem init-wedding-local-filesystem}})

;; TODO: TEST ME!
(defn start-system!
  [launch-options env]
  (reduce (fn [acc {:keys [name path launchers] :as system-component}]
            (let [init-fn (->> path
                               (get-in launch-options)
                               (get launchers))]
              (log/debugf "Starting %s using %s" name init-fn)
              (assoc acc path (init-fn env))))
          {}
          [database-boot-instructions

           andrewslai-authentication-boot-instructions
           andrewslai-authorization-boot-instructions
           andrewslai-static-content-adapter-boot-instructions

           wedding-authentication-boot-instructions
           wedding-authorization-boot-instructions
           wedding-static-content-adapter-boot-instructions
           ]))


(comment
  (start-system! (environment->launch-options {"ANDREWSLAI_DB_TYPE"   "embedded-h2"
                                               "ANDREWSLAI_AUTH_TYPE" "always-unauthenticated"
                                               "ANDREWSLAI_WEDDING_AUTH_TYPE" "always-unauthenticated"})
                 {})
  )

;; Updates the Malli schema validation output to only show the errors
;; This will:
;; (1) Make sure we don't log secrets
;; (2) Focus on errors so the user gets direct feedback about what to fix
(defmethod v/-format ::m/invalid-output [_ _ {:keys [value args output fn-name]} printer]
  {:body
   [:group
    (pretty/-block "Invalid function return value. Function Var:" (v/-visit fn-name printer) printer) :break :break
    (pretty/-block "Errors:" (pretty/-explain output value printer) printer) :break :break]})

(mi/collect! {:ns 'andrewslai.clj.init.env})
(mi/instrument! {:report (pretty/thrower)})


;; defn prepare-for-virtual-hosting
