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
            [next.jdbc :as next]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Launch Options Map
;; Parse environment variables into a map of `launch-options`:
;; the minimal amount of information needed to launch a webserver.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn environment->launch-options
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

(def database :database)
(def andrewslai :andrewslai)
(def wedding :wedding)
(def authentication-type :authentication-type)
(def authorization-type :authorization-type)
(def static-content-type :static-content-type)

(def database-type (comp :db-type database))
(def andrewslai-authentication-type (comp authentication-type andrewslai))
(def andrewslai-authorization-type  (comp authorization-type  andrewslai))
(def andrewslai-static-content-type (comp static-content-type andrewslai))

(def wedding-authentication-type (comp authentication-type wedding))
(def wedding-authorization-type  (comp authorization-type  wedding))
(def wedding-static-content-type (comp static-content-type wedding))

;; Supported launch options
;;
;; Authentication
(def keycloak?               (partial = :keycloak))
(def always-authenticated?   (partial = :always-authenticated))
(def always-unauthenticated? (partial = :always-unauthenticated))

;; Database
(def postgres?          (partial = :postgres))
(def embedded-postgres? (partial = :embedded-postgres))
(def embedded-h2?       (partial = :embedded-h2))

;; Static File Adapter
(def s3?                (partial = :s3))
(def in-memory?         (partial = :in-memory))
(def local-filesystem?  (partial = :local-filesystem))

;; Authorization
(def public-access?           (partial = :public-access))
(def use-access-control-list? (partial = :use-access-control-list))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration for components
;;
;; After parsing into a `launch-options` map, the config
;; namespace will continue booting pieces/components of the system
;; depending on which launch options were selected
;;
;; e.g. if `:keycloak` authentication was selected,
;;      the `env->keycloak` helper will parse relevant
;;      keycloak environment variables into a configuration
;;      map that can be used to boot the keycloak component.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn env->keycloak
  [env]
  {:realm             (get env "ANDREWSLAI_AUTH_REALM")
   :auth-server-url   (get env "ANDREWSLAI_AUTH_URL")
   :client-id         (get env "ANDREWSLAI_AUTH_CLIENT")
   :client-secret     (get env "ANDREWSLAI_AUTH_SECRET")
   :ssl-required      "external"
   :confidential-port 0})

(def init-andrewslai-keycloak             (comp bb/keycloak-backend env->keycloak))
(def init-andrewslai-authenticated-user   bb/authenticated-backend)
(def init-andrewslai-unauthenticated-user (constantly bb/unauthenticated-backend))
(def init-andrewslai-public-access        (constantly tu/public-access))
(def init-andrewslai-access-control       (constantly andrewslai/ANDREWSLAI-ACCESS-CONTROL-LIST))
(def init-andrewslai-s3-filesystem        s3-storage/andrewslai-s3-from-env)
(def init-andrewslai-in-memory-filesystem memory/in-mem-fs-from-env)
(def init-andrewslai-local-filesystem     local-fs/andrewslai-local-fs-from-env)

(def init-wedding-keycloak             (comp bb/keycloak-backend env->keycloak))
(def init-wedding-authenticated-user   bb/authenticated-backend)
(def init-wedding-unauthenticated-user (constantly bb/unauthenticated-backend))
(def init-wedding-public-access        (constantly tu/public-access))
(def init-wedding-access-control       (constantly wedding/WEDDING-ACCESS-CONTROL-LIST))
(def init-wedding-s3-filesystem        s3-storage/wedding-s3-from-env)
(def init-wedding-in-memory-filesystem memory/in-mem-fs-from-env)
(def init-wedding-local-filesystem     local-fs/wedding-local-fs-from-env)

(def PostgresConnectionMap
  [:map
   [:dbname   [:string {:error/message "Missing DB name. Set via ANDREWSLAI_DB_NAME environment variable."}]]
   [:db-port  [:string {:error/message "Missing DB port. Set via ANDREWSLAI_DB_PORT environment variable."}]]
   [:host     [:string {:error/message "Missing DB host. Set via ANDREWSLAI_DB_HOST environment variable."}]]
   [:user     [:string {:error/message "Missing DB user. Set via ANDREWSLAI_DB_USER environment variable."}]]
   [:password [:string {:error/message "Missing DB pass. Set via ANDREWSLAI_DB_PASSWORD environment variable."}]]
   [:dbtype   [:string {:error/message "Missing DB type. Set in code. Should never happen."}]]])

(defn env->pg-conn
  {:malli/schema [:=> [:cat :map] PostgresConnectionMap]}
  [env]
  {:dbname   (get env "ANDREWSLAI_DB_NAME")
   :db-port  (get env "ANDREWSLAI_DB_PORT" "5432")
   :host     (get env "ANDREWSLAI_DB_HOST")
   :user     (get env "ANDREWSLAI_DB_USER")
   :password (get env "ANDREWSLAI_DB_PASSWORD")
   :dbtype   "postgresql"})

(def init-postgres-connection          (comp next/get-datasource env->pg-conn))
(def init-embedded-postgres-connection (fn [x] (embedded-pg/fresh-db!)))
(def init-embedded-h2-connection       (fn [x] (embedded-h2/fresh-db!)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Configuration map
;; Parse environment variables into a map of config values:
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def system-blueprint
  {database-type [:database]

   andrewslai-authentication-type [:andrewslai :authentication-backend]
   andrewslai-authorization-type  [:andrewslai :authorization]
   andrewslai-static-content-type [:andrewslai :static-content-adapter]

   wedding-authentication-type [:wedding :authentication-backend]
   wedding-authorization-type  [:wedding :authorization]
   wedding-static-content-type [:wedding :static-content-adapter]
   })

(def boot-instructions
  {database-type {:postgres          init-embedded-postgres-connection
                  :embedded-h2       init-embedded-h2-connection
                  :embedded-postgres init-embedded-postgres-connection}

   andrewslai-authentication-type {:keycloak                  init-andrewslai-keycloak
                                   :always-unauthenticated    init-andrewslai-unauthenticated-user
                                   :custom-authenticated-user init-andrewslai-authenticated-user}
   andrewslai-authorization-type  {:public-access           init-andrewslai-public-access
                                   :use-access-control-list init-andrewslai-access-control}
   andrewslai-static-content-type {:s3               init-andrewslai-s3-filesystem
                                   :in-memory        init-andrewslai-in-memory-filesystem
                                   :local-filesystem init-andrewslai-local-filesystem}

   wedding-authentication-type {:keycloak                  init-wedding-keycloak
                                :always-unauthenticated    init-wedding-unauthenticated-user
                                :custom-authenticated-user init-wedding-authenticated-user}
   wedding-authorization-type  {:public-access           init-wedding-public-access
                                :use-access-control-list init-wedding-access-control}
   wedding-static-content-type {:s3               init-wedding-s3-filesystem
                                :in-memory        init-wedding-in-memory-filesystem
                                :local-filesystem init-wedding-local-filesystem}
   })

(defn start-system!
  [launch-options env]
  (reduce-kv (fn [acc lookup-component launcher-map]
               (let [component-type (lookup-component launch-options)
                     init-fn        (get launcher-map component-type)]
                 (assoc-in acc (get system-blueprint lookup-component) (init-fn env))))
             {}
             boot-instructions))

(defmethod v/-format ::m/invalid-output [_ _ {:keys [value args output fn-name]} printer]
  {:body
   [:group
    (pretty/-block "Invalid function return value. Function Var:" (v/-visit fn-name printer) printer) :break :break
    (pretty/-block "Errors:" (pretty/-explain output value printer) printer) :break :break]})

(mi/collect! {:ns 'andrewslai.clj.init.env})
(mi/instrument! {:report (pretty/thrower)})


;; defn prepare-for-virtual-hosting

(comment
  (def config
    (environment->launch-options (System/getenv)))

  (build-config-map config (System/getenv))
  )

(comment

  (def using-postgres?            (comp postgres?          database-type))
  (def using-embedded-postgres?   (comp embedded-postgres? database-type))
  (def using-embedded-h2?         (comp embedded-h2?       database-type))

  (def andrewslai-using-keycloak? (comp keycloak?         andrewslai-authentication-type))
  (def andrewslai-using-s3?       (comp s3?               andrewslai-static-content-type))
  (def andrewslai-using-local-fs? (comp local-filesystem? andrewslai-static-content-type))

  (def wedding-using-keycloak? (comp keycloak?         wedding-authentication-type))
  (def wedding-using-s3?       (comp s3?               wedding-static-content-type))
  (def wedding-using-local-fs? (comp local-filesystem? wedding-static-content-type))



  )
