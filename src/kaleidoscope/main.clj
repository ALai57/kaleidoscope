(ns kaleidoscope.main
  (:gen-class)
  (:require [cheshire.core :as json]
            [clojure.string :as string]
            [clojure.pprint :as pprint]
            [kaleidoscope.http-api.middleware :as mw]
            [kaleidoscope.models.registry] ;; load malli extensions for time
            [kaleidoscope.init.env :as env]
            [kaleidoscope.utils.logging :as ul]
            [less.awful.ssl :as less-ssl]
            [malli.dev.pretty :as pretty]
            [malli.instrument :as mi]
            [ring.adapter.jetty :as jetty]
            [signal.handler :as sig]
            [signal.amazonica-aws-sso :as amazonica-aws-sso]
            [steffan-westcott.clj-otel.exporter.otlp.http.trace :as otlp-http-trace]
            [steffan-westcott.clj-otel.resource.resources :as res]
            [steffan-westcott.clj-otel.sdk.otel-sdk :as sdk]
            [taoensso.timbre :as log]
            [taoensso.timbre.appenders.core :as appenders]))

(try
  (when-let [aws-profile (System/getenv "AWS_PROFILE")]
    (log/infof "Attempting to use AWS_PROFILE: %s" aws-profile)
    (amazonica-aws-sso/init!))
  (catch Exception e
    (log/warn "Unable to set up SSO provider!")))

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
   (cond-> {:min-level [["io.zonky*" :error]
                        ["org.eclipse.jetty.*" :warn]
                        ["*" (keyword (get env "KALEIDOSCOPE_LOG_LEVEL" "info"))]]
            :output-fn json-log-output
            :appenders {:spit (appenders/spit-appender {:fname "log.txt"})}}
     (disable-json-logging? env) (assoc :output-fn ul/clean-output-fn))))

(defn init-otel! []
  (sdk/init-otel-sdk! "kaleidoscope"
                      {:resources [(res/host-resource)
                                   (res/os-resource)
                                   (res/process-resource)
                                   (res/process-runtime-resource)]
                       :tracer-provider {:span-processors [{:exporters [(otlp-http-trace/span-exporter)]}]}}))

(defn close-otel! []
  (sdk/close-otel-sdk!))

(defn initialize-schema-enforcement!
  "Enforce Malli function schemas in specific namespaces.
  Used to ensure that the given environment variables result in a valid system
  configuration."
  []
  (mi/collect! {:ns 'kaleidoscope.init.env})
  (mi/instrument! {:report (pretty/thrower)}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Running the server
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn start-application!
  "Kaleidoscope expects a Load balancer to use TLS termination so it receives HTTP requests.
  However, for testing, it can be useful to have HTTPS capabilities (especially for Cognito)"
  [env]
  (let [system-components (env/start-system! env)
        http-port         5000

        ;; https://github.com/ring-clojure/ring/blob/246f599b47adaa7c74175f84b4cd4398f06f72d9/ring-jetty-adapter/test/ring/adapter/test/jetty.clj#L180
        ;; https://github.com/aphyr/less-awful-ssl
        ssl-props (if (get env "KALEIDOSCOPE_ENABLE_SSL")
                    (do (log/info "Adding SSL to Jetty server startup. Listening to HTTPS on port 5443")
                        {:ssl?        true
                         :ssl-port    5443
                         :ssl-context (less-ssl/ssl-context "./resources/ssl/andrewslai.localhost-key.pem"
                                                            "./resources/ssl/andrewslai.localhost.pem"
                                                            "./resources/ssl/andrewslai.localhost.pem"
                                                            )})
                    (log/info "Skipping SSL startup"))]
    (log/infof "Starting kaleidoscope. Listening to HTTP on port %s" http-port)
    (initialize-logging! env)
    (init-otel!)
    (initialize-schema-enforcement!)
    (-> system-components
        (env/make-http-handler)
        (jetty/run-jetty (cond-> {:port http-port}
                           ssl-props (merge ssl-props))))))

(defn -main
  "Start a server and run the application"
  [& args]

  ;; Cannot test this via REPL. Need to run this in a Java process,
  ;; `./bin/uberjar`
  ;; `java -jar target/kaleidoscope.jar`
  ;; https://grishaev.me/en/clj-book-systems/
  (sig/with-handler :term
    (log/fatal "Caught SIGTERM, quitting.")
    (System/exit 0))

  (sig/with-handler :hup
    (log/info "Caught SIGHUP, doing nothing."))

  (start-application! (into {} (System/getenv))) ;; b/c getenv returns java.util.Collections$UnmodifiableMap
  )

(comment
  (def example-json-string
    (->> {:a "Lots of things"
          :c {:d :foo :e "bar"}
          :f {:a "b" :c {:d :foo :e "bar"}}}
         pprint/pprint
         with-out-str))

  ;; Log using JSON output formatting
  (log/with-merged-config
    {:output-fn json-log-output}
    (log/info example-json-string)))

(comment
  (def example-system
    "Starts up the dependencies that would be injected into the HTTP handler"
    (env/start-system! {"KALEIDOSCOPE_DB_TYPE"                     "embedded-h2"
                        "KALEIDOSCOPE_AUTH_TYPE"                   "custom-authenticated-user"
                        "KALEIDOSCOPE_AUTHORIZATION_TYPE"          "public-access"
                        "KALEIDOSCOPE_STATIC_CONTENT_TYPE"         "none"
                        "KALEIDOSCOPE_WEDDING_AUTH_TYPE"           "custom-authenticated-user"
                        "KALEIDOSCOPE_WEDDING_AUTHORIZATION_TYPE"  "public-access"
                        "KALEIDOSCOPE_WEDDING_STATIC_CONTENT_TYPE" "none"})))

(comment
  ;; Play with AWS SSO
  (require '[amazonica.aws.securitytoken :as sts])

  ;; Use SSO credentials for a single call
  (amazonica-aws-sso/with-sso-credential
    (sts/get-caller-identity))

  ;; Use SSO credentials for all subsequent calls
  (amazonica-aws-sso/init!)
  (sts/get-caller-identity)

  ;; Reset amazonica to use it's default AWS credentials provider
  (amazonica-aws-sso/reset!)
  )
