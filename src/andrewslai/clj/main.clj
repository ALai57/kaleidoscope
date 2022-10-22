(ns andrewslai.clj.main
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.init.config :as config]
            [andrewslai.clj.utils.core :as util]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global settings (Yuck!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn initialize!
  []
  (log/merge-config!
   {:min-level :info
    :appenders {:spit (appenders/spit-appender {:fname "log.txt"})}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-app
  [ring-handler {:keys [port] :as configuration}]
  (http/start-server ring-handler {:port port}))

(defn -main
  "Start a server and run the application"
  [& args]
  (let [{:keys [port] :as configuration} (config/configure-from-env (System/getenv))]
    (log/infof "Hello! Starting andrewslai on port %s" port)
    (initialize!)
    (start-app (config/configure-http-handler configuration)
               configuration)))
