(ns kaleidoscope.clients.bugsnag
  (:import [com.bugsnag Bugsnag]))

(defn make-bugsnag-notifier
  [{:keys [api-key app-version] :as bugsnag-config}]
  (doto (Bugsnag. api-key)
    (.setAppVersion (or app-version "version-not-set"))
    (.setProjectPackages (into-array ["kaleidoscope"]))
    (.setAppType "kaleidoscope-backend")))

(defn notify!
  [^com.bugsnag.Bugsnag bugsnag e]
  (.notify bugsnag e))

(comment
  (def notifier
    (make-bugsnag-notifier {:api-key "API-KEY-HERE"}))

  (notify! notifier (Exception. "Exception!"))
  )
