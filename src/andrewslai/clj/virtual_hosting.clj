(ns andrewslai.clj.virtual-hosting
  (:gen-class)
  (:require [clojure.spec.alpha :as s]
            [ring.util.request :as req]
            [taoensso.timbre :as log]))

(defn regex?
  [x]
  (= java.util.regex.Pattern (type x)))

(s/def :virtual-hosts/url regex?)
(s/def :virtual-hosts/app ifn?)
(s/def :virtual-hosts/priority int?)
(s/def :virtual-hosts/configuration
  (s/keys :req-un [:virtual-hosts/app]
          :opt-un [:virtual-hosts/priority]))
(s/def :virtual-hosts/host
  (s/cat :url :virtual-hosts/url :config :virtual-hosts/configuration))
(s/def :virtual-hosts/hosts
  (s/map-of :virtual-hosts/url :virtual-hosts/configuration))


(defn get-app
  [[url {:keys [app]}]]
  app)

(defn get-host-url
  [virtual-host]
  (first virtual-host))

(defn get-priority
  "Lower numbers indicate higher priority"
  [virtual-host]
  (or (:priority (second virtual-host)) 0))

(defn matching-url?
  [request host-url]
  (some? (re-find host-url (req/request-url request))))

(defn select-app
  "Select an app to route the request to"
  [request virtual-hosts]
  (let [[url {:keys [app]}] (->> virtual-hosts
                                 (filter (fn [virtual-host]
                                           (matching-url? request (get-host-url virtual-host))))
                                 (sort-by get-priority)
                                 (first))]
    (log/infof "Virtual hosting: routing to `%s`" url)
    app))

(defn host-based-routing
  "Route a request to one of the supplied apps"
  [virtual-hosts]
  (fn
    [request]
    (log/infof "Virtual hosting: routing request for `%s`" (req/request-url request))
    (if-let [app (select-app request virtual-hosts)]
      (app request)
      (let [msg (format "Unrecognized host %s." (req/request-url request))]
        (log/errorf msg)
        (throw (IllegalArgumentException. msg))))))

(s/def :network/protocol #{"http" "https"})
(s/def :network/domain string?)
(s/def :network/port pos-int?)
(s/def :network/host string?)
(s/def :network/endpoint string?)

(s/def :network/query string?)
(s/def :network/fragment string?)

(s/def :network/url string?)
