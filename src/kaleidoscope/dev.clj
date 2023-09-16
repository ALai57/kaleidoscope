(ns kaleidoscope.dev
  (:require [kaleidoscope.init.env :as env]
            [kaleidoscope.main :as main]
            [ring.adapter.jetty :as jetty]
            [ring.middleware.reload :as reload]
            [taoensso.timbre :as log]))

(defn start-dev-application!
  [handler]
  (let [port 5000]
    (log/infof "Hello! Starting kaleidoscope on port %s" port)
    (jetty/run-jetty handler
                     {:port  port
                      :join? false})))

(def dev-handler
  "Evaluate this buffer to reload the `dev-handler` and have it show up
  immediately in the running webserver"
  (delay (let [env (into {} (System/getenv))]
           (main/initialize-logging! env)
           (env/make-http-handler (env/start-system! env)))))

(comment
  (def dev-app
    (start-dev-application! (reload/wrap-reload @#'dev-handler)))

  (.stop dev-app)
  )
