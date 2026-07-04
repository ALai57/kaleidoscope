(ns kaleidoscope.clients.bugsnag
  (:require [kaleidoscope.clients.error-reporter :as er]
            [kaleidoscope.clients.session-tracker :as st])
  (:import [com.bugsnag Bugsnag Report]
           [com.bugsnag.callbacks Callback]))

(defn- initialize
  [{:keys [api-key app-version] :as bugsnag-config}]
  (doto (Bugsnag. api-key)
    (.setAppVersion (or app-version "version-not-set"))
    (.setProjectPackages (into-array ["kaleidoscope"]))
    (.setAppType "kaleidoscope-backend")
    (.setAutoCaptureSessions false)))

(defn- tab-value
  [v]
  (if (string? v) v (pr-str v)))

(defn- add-ex-data-tab!
  "Bugsnag only sees an exception's class and message, so an ex-info like
  `(ex-info \"S3 get-file error\" {:cognitect.anomalies/category ...})` shows
  up with no way to tell what actually went wrong short of grepping logs.
  Surface the ex-data as a metadata tab so the detail is visible directly in
  Bugsnag."
  [^Report report e]
  (doseq [[k v] (ex-data e)]
    (.addToTab report "ex-data" (str (symbol k)) (tab-value v)))
  report)

(defrecord BugsnagClient [client api-key app-version]
  er/ErrorReporter
  (report! [this e]
    (.notify client e
             (reify Callback
               (beforeNotify [_ report]
                 (add-ex-data-tab! report e)))))

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

  #_:clj-kondo/ignore
  (report! notifier (Exception. "Exception!"))
  )
