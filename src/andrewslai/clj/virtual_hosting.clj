(ns andrewslai.clj.virtual-hosting
  (:gen-class)
  (:require [clojure.spec.alpha :as s]
            [ring.util.request :as req]))


(defn get-virtual-host-url
  [virtual-host]
  (first virtual-host))

(defn get-priority
  [virtual-host]
  (or (:priority (second virtual-host)) 0))

(defn matching-url?
  [request candidate-url]
  (some? (re-find candidate-url (req/request-url request))))

(defn route
  "Route a given request to one of the supplied virtual hosts"
  [request virtual-hosts]
  (->> virtual-hosts
       (filter (fn [virtual-host]
                 (matching-url? request (get-virtual-host-url virtual-host))))
       (sort-by get-priority)
       (first)))

(defn host-based-routing
  [virtual-hosts]
  (fn
    ([request]
     ((route request virtual-hosts) request))
    ([request respond raise]
     ((route request virtual-hosts) request respond raise))))

(s/def :network/protocol #{"http" "https"})
(s/def :network/domain string?)
(s/def :network/port pos-int?)
(s/def :network/host string?)
(s/def :network/endpoint string?)

(s/def :network/query string?)
(s/def :network/fragment string?)

(s/def :network/url string?)
