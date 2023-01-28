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
;; Launching the system
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-middleware
  "Middleware handles Authentication, Authorization"
  ([launch-options]
   (make-middleware launch-options {}))
  ([{:keys [authentication authorization] :as system}
    env]
   (comp mw/standard-stack
         (mw/auth-stack authentication authorization))))

(defn make-http-handler
  [{:keys [andrewslai wedding] :as components}]
  (vh/host-based-routing
   {#"caheriaguilar.and.andrewslai.com" {:priority 0
                                         :app      (wedding/wedding-app wedding)}
    #".*"                               {:priority 100
                                         :app      (andrewslai/andrewslai-app andrewslai)}}))
