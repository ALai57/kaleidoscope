(ns andrewslai.clj.main
  (:gen-class)
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.config :as cfg]))

(defn -main
  "Start a server and run the application"
  [& args]
  (let [{:keys [port] :as configuration} (cfg/configure-from-env (System/getenv))]
    (println "Hello! Starting andrewslai on port" port)
    (h/start-app configuration)))
