(ns kaleidoscope.main
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.init.env :as env]
            [ring.adapter.jetty :as jetty]
            [steffan-westcott.clj-otel.exporter.otlp.http.trace :as otlp-http-trace]
            [steffan-westcott.clj-otel.resource.resources :as res]
            [steffan-westcott.clj-otel.sdk.otel-sdk :as sdk]
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


(defn init-otel! []
  (sdk/init-otel-sdk! "kaleidoscope"
                      {:resources [(res/host-resource)
                                   (res/os-resource)
                                   (res/process-resource)
                                   (res/process-runtime-resource)
                                   ]
                       :tracer-provider {:span-processors [{:exporters [(otlp-http-trace/span-exporter)]}]}}))

(defn close-otel! []
  (sdk/close-otel-sdk!))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-application!
  [env]
  (let [system-components (env/start-system! env)
        port              5000]
    (log/infof "Hello! Starting andrewslai on port %s" port)
    (initialize-logging! env)
    (init-otel!)
    (-> system-components
        (env/prepare-for-virtual-hosting)
        (env/make-http-handler)
        (jetty/run-jetty {:port port}))))

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