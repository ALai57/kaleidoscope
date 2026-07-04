(ns kaleidoscope.clients.bugsnag
  (:require [kaleidoscope.clients.error-reporter :as er]
            [kaleidoscope.clients.session-tracker :as st])
  (:import [com.bugsnag Bugsnag Report]
           [com.bugsnag.callbacks Callback]))

(defn- initialize
  [{:keys [api-key app-version release-stage] :as bugsnag-config}]
  (doto (Bugsnag. api-key)
    (.setAppVersion (or app-version "version-not-set"))
    (.setProjectPackages (into-array ["kaleidoscope"]))
    (.setAppType "kaleidoscope-backend")
    (.setAutoCaptureSessions false)
    (.setReleaseStage (or release-stage "production"))))

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

(defn- set-persistence-grouping-hash!
  "Every query in kaleidoscope.persistence.rdbms funnels through a handful of
  shared call sites (see wrap-sql-exceptions there), so Bugsnag's default
  frame-based grouping merges completely unrelated SQL errors - wrong table,
  bad types, timeouts - into a single 'error' just because they threw from
  the same line. Group by table + SQLState instead, which is what actually
  distinguishes one DB failure from another."
  [^Report report e]
  (let [{:keys [type table sql-state]} (ex-data e)]
    (when (= type :PersistenceException)
      (.setGroupingHash report (str "PersistenceException:" sql-state ":" table))))
  report)

(defrecord BugsnagClient [client api-key app-version release-stage]
  er/ErrorReporter
  (report! [this e]
    (.notify client e
             (reify Callback
               (beforeNotify [_ report]
                 (add-ex-data-tab! report e)
                 (set-persistence-grouping-hash! report e)))))

  st/SessionTracker
  (start! [this]
    (.startSession client)))

(defn make-bugsnag-client
  [{:keys [api-key app-version release-stage] :as bugsnag-config}]
  (map->BugsnagClient {:api-key       api-key
                       :app-version   app-version
                       :release-stage release-stage
                       :client        (initialize bugsnag-config)}))

(comment
  (def notifier
    (make-bugsnag-client {:api-key "API-KEY-HERE"}))

  #_:clj-kondo/ignore
  (report! notifier (Exception. "Exception!"))
  )
