(ns kaleidoscope.clients.bugsnag
  (:require [kaleidoscope.clients.error-reporter :as er]
            [kaleidoscope.clients.session-tracker :as st])
  (:import [com.bugsnag Bugsnag]))

(defn- initialize
  [{:keys [api-key app-version] :as bugsnag-config}]
  (doto (Bugsnag. api-key)
    (.setAppVersion (or app-version "version-not-set"))
    (.setProjectPackages (into-array ["kaleidoscope"]))
    (.setAppType "kaleidoscope-backend")
    (.setAutoCaptureSessions false)))

(defrecord BugsnagClient [client api-key app-version]
  er/ErrorReporter
  (report! [this e]
    (.notify client e))

  st/SessionTracker
  (start! [this]
    (.startSession client)))

(defn make-bugsnag-client
  [{:keys [api-key app-version] :as bugsnag-config}]
  (map->BugsnagClient {:api-key     api-key
                       :app-version app-version
                       :client      (initialize bugsnag-config)}))

(comment
  (def notifier
    (make-bugsnag-client {:api-key "API-KEY-HERE"}))

  (report! notifier (Exception. "Exception!"))
  )
