(ns andrewslai.clj.init.config
  (:require [andrewslai.clj.api.authorization :as auth]
            [andrewslai.clj.http-api.andrewslai :as andrewslai]
            [andrewslai.clj.http-api.auth.buddy-backends :as bb]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.http-api.virtual-hosting :as vh]
            [andrewslai.clj.http-api.wedding :as wedding]
            [andrewslai.clj.init.env :as env]
            [andrewslai.clj.persistence.filesystem.in-memory-impl :as memory]
            [andrewslai.clj.persistence.filesystem.local :as local-fs]
            [andrewslai.clj.persistence.filesystem.s3-impl :as s3-storage]
            [andrewslai.clj.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [andrewslai.clj.persistence.rdbms.embedded-postgres-impl :as embedded-pg]
            [andrewslai.clj.persistence.rdbms.live-pg :as live-pg]
            [andrewslai.clj.test-utils :as tu]
            [next.jdbc :as next]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Access control
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def public-access (constantly true))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Launching the system
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def launch-options
  (env/environment->launch-options (System/getenv)))

(defn make-logging
  [{:keys [level] :as launch-options} env]
  (merge log/*config* {:min-level level}))

(defn make-database-connection
  [{:keys [database] :as config-map} env]
  (try
    (log/infof "Starting Database connection to %s" (:db-type database))
    (case (:db-type database)
      :postgres          (next/get-datasource (:connection database))
      :embedded-postgres (embedded-pg/fresh-db!)
      :embedded-h2       (embedded-h2/fresh-db!))
    (catch Throwable e
      (log/errorf "Failed to start database %s. Check your environment variables." (:db-type database))
      (throw e))))

;; Static content Adapter (a Filesystem-like object)
(def example-fs
  "An in-memory filesystem used for testing"
  {"index.html" (memory/file {:name    "index.html"
                              :content "<div>Hello</div>"})})

(defn make-static-content-adapter
  [{:keys [static-content-type] :as launch-options} env]
  (case static-content-type
    :s3               (s3-storage/map->S3 {:bucket (:bucket launch-options)
                                           :creds  s3-storage/CustomAWSCredentialsProviderChain})
    :in-memory        (memory/map->MemFS {:store (atom example-fs)})
    :local-filesystem (local-fs/map->LocalFS {:root (:folder launch-options)})
    :none             identity))

(defn make-middleware
  "Middleware handles Authentication, Authorization"
  ([launch-options]
   (make-middleware launch-options {}))
  ([{:keys [authorization-type custom-access-rules
            authentication-type custom-authenticated-user]
     :as launch-options}
    env]
   (comp mw/standard-stack
         (mw/auth-stack (case authentication-type
                          :keycloak                     (bb/keycloak-backend (:keycloak launch-options))
                          :always-unauthenticated       bb/unauthenticated-backend
                          :custom-authenticated-user    (bb/authenticated-backend custom-authenticated-user))
                        (case authorization-type
                          :public-access           tu/public-access
                          :use-access-control-list custom-access-rules)))))

(defn initialize-system!
  [{:keys [wedding andrewslai] :as config-map} env]
  (let [database-connection (make-database-connection config-map env)]
    {:andrewslai {:database               database-connection
                  :http-mw                (make-middleware andrewslai env)
                  :static-content-adapter (make-static-content-adapter andrewslai env)}
     :wedding    {:database               database-connection
                  :http-mw                (make-middleware wedding env)
                  :static-content-adapter (make-static-content-adapter wedding env)
                  :logging                (make-logging config-map env)}}))

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
