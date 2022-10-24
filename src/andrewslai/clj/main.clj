(ns andrewslai.clj.main
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.init.config :as config]
            [andrewslai.clj.utils.core :as util]
            [clojure.string :as string]
            [cheshire.core :as json]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Global settings (Yuck!)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- json-log-output
  [{:keys [level msg_ instant ?ns-str ?file ?line] :as data}]
  (let [event    (force msg_)
        ns-name  (or ?ns-str ?file "?")
        line-num (or ?line "?")]
    (json/generate-string {:timestamp  instant
                           :level      level
                           :ns         ns-name
                           :request-id mw/*request-id*
                           :line       (format "%s:%s" ns-name line-num)
                           :message    (string/replace event #"\n" " ")})))

(defn initialize!
  []
  (log/merge-config!
   {:min-level :info
    :output-fn json-log-output
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

(comment
  (log/with-merged-config
    {:output-fn json-log-output}
    (log/info (->> {:a "bbig long bits of stuff and more and more"
                    :c {:d :foo :e "bar"}
                    :f {:a "b" :c {:d :foo :e "bar"}}}
                   clojure.pprint/pprint
                   with-out-str)))
  )
