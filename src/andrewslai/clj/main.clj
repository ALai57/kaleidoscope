(ns andrewslai.clj.main
  (:gen-class)
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.config :as cfg]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global settings (Yuck!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(log/merge-config!
 {:appenders {:spit (appenders/spit-appender {:fname "log.txt"})}})

(defn -main
  "Start a server and run the application"
  [& args]
  (let [{:keys [port] :as configuration} (cfg/configure-from-env (System/getenv))]
    (log/infof "Hello! Starting andrewslai on port %s" port)
    (h/start-app configuration)))
