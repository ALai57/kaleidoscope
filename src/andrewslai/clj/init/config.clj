(ns andrewslai.clj.init.config
  (:require
   [andrewslai.clj.http-api.andrewslai :as andrewslai]
   [andrewslai.clj.http-api.middleware :as mw]
   [andrewslai.clj.http-api.virtual-hosting :as vh]
   [andrewslai.clj.http-api.wedding :as wedding]))

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
