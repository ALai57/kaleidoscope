(ns andrewslai.clj.main
  (:gen-class)
  (:require [aleph.http :as http]
            [andrewslai.clj.http-api.middleware :as mw]
            [andrewslai.clj.init.config :as config]
            [cheshire.core :as json]
            [clojure.string :as string]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]
            [andrewslai.clj.init.env :as env]))

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

(defn initialize-logging!
  []
  (log/merge-config!
   {:min-level :info
    :output-fn json-log-output
    :appenders {:spit (appenders/spit-appender {:fname "log.txt"})}}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-application!
  [env]
  (let [system-components (env/start-system! env)
        port              5000]
    (log/infof "Hello! Starting andrewslai on port %s" port)
    (initialize-logging!)
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
    (env/start-system! {"ANDREWSLAI_DB_TYPE"                     "embedded-h2"
                        "ANDREWSLAI_AUTH_TYPE"                   "custom-authenticated-user"
                        "ANDREWSLAI_AUTHORIZATION_TYPE"          "public-access"
                        "ANDREWSLAI_STATIC_CONTENT_TYPE"         "none"
                        "ANDREWSLAI_WEDDING_AUTH_TYPE"           "custom-authenticated-user"
                        "ANDREWSLAI_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                        "ANDREWSLAI_WEDDING_STATIC_CONTENT_TYPE" "none"
                        }
                       ))

  (env/prepare-for-virtual-hosting x)

  )
