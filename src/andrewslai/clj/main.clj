(ns andrewslai.clj.main
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.config :as cfg]
            [andrewslai.clj.handler :as h]
            [andrewslai.clj.routes.andrewslai :as andrewslai]
            [andrewslai.clj.routes.wedding :as wedding]
            [andrewslai.clj.virtual-hosting :as vh]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global settings (Yuck!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(log/merge-config!
 {:appenders {:spit (appenders/spit-appender {:fname "log.txt"})}})

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-app
  [{:keys [port andrewslai wedding] :as configuration}]
  (http/start-server
   (vh/host-based-routing
    {#"caheriaguilar.and.andrewslai.com" {:priority 0
                                          :app      (wedding/wedding-app wedding)}
     #".*"                               {:priority 100
                                          :app      (andrewslai/andrewslai-app andrewslai)}})
   {:port port}))

(defn -main
  "Start a server and run the application"
  [& args]
  (let [{:keys [port] :as configuration} (cfg/configure-from-env (System/getenv))]
    (log/infof "Hello! Starting andrewslai on port %s" port)
    (h/start-app configuration)))
