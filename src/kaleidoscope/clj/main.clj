(ns kaleidoscope.clj.main
  (:gen-class)
  (:require [aleph.http :as http]
            [kaleidoscope.clj.http-api.middleware :as mw]
            [kaleidoscope.clj.init.env :as env]
            [cheshire.core :as json]
            [clojure.string :as string]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Logging
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- json-log-output
  "Useful when shipping logs via FluentBit (the recommended log router for AWS
  ECS applications)
  https://docs.aws.amazon.com/AmazonECS/latest/developerguide/firelens-using-fluentbit.html"
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

(defn disable-json-logging?
  "Useful when running locally to avoid JSON structured logs."
  [env]
  (parse-boolean (get env "DISABLE_JSON_LOGGING" "false")))

(defn initialize-logging!
  [env]
  (log/merge-config!
   (cond-> {:min-level :info
            :output-fn json-log-output
            :appenders {:spit (appenders/spit-appender {:fname "log.txt"})}}
     (disable-json-logging? env) (dissoc :output-fn))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-application!
  [env]
  (let [system-components (env/start-system! env)
        port              5000]
    (log/infof "Hello! Starting andrewslai on port %s" port)
    (initialize-logging! env)
    (-> system-components
        (env/prepare-for-virtual-hosting)
        (env/make-http-handler)
        (http/start-server {:port port}))))

(defn -main
  "Start a server and run the application"
  [& args]
  (start-application! (into {} (System/getenv))) ;; b/c getenv returns java.util.Collections$UnmodifiableMap
  )

(comment
  (log/with-merged-config
    {:output-fn json-log-output}
    (log/info (->> {:a "bbig long bits of stuff and more and more"
                    :c {:d :foo :e "bar"}
                    :f {:a "b" :c ({:d :foo :e "bar"})}}
                   clojure.pprint/pprint
                   with-out-str)))

  (def x
    (env/start-system! {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                        "KALEIDOSCOPE_AUTH_TYPE"                   "custom-authenticated-user"
                        "KALEIDOSCOPE_AUTHORIZATION_TYPE"          "public-access"
                        "KALEIDOSCOPE_STATIC_CONTENT_TYPE"         "none"
                        "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "custom-authenticated-user"
                        "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                        "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "none"
                        }
                       ))

  (env/prepare-for-virtual-hosting x)

  )
