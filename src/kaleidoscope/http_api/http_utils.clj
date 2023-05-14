(ns kaleidoscope.http-api.http-utils
  (:require
   [clojure.string :as str]
   [camel-snake-kebab.core :as csk]
   [camel-snake-kebab.extras :as cske]
   [kaleidoscope.http-api.cache-control :as cc]
   [kaleidoscope.persistence.filesystem :as fs]
   [ring.util.http-response :refer [not-found not-modified]]
   [taoensso.timbre :as log]))

(defn remove-port
  [hostname]
  (first (string/split hostname #":")))

(defn get-host
  [request]
  (-> request
      (get-in [:headers "host"])
      (remove-port)))

(defn kebab-case-headers
  [{:keys [headers] :as request}]
  (cond-> request
    headers (assoc :headers (cske/transform-keys csk/->kebab-case headers))))

(defn bucket-name
  "Getting host name is from ring.util.request"
  [request]
  (let [server-name (get-in request [:headers "host"])]
    (when (nil? server-name)
      (log/warnf "Request without a host. Cannot lookup associated bucket."))
    (str/join "." (butlast (str/split server-name #"\.")))))

(defn get-resource
  [static-content-adapters {:keys [uri headers] :as request}]
  (let [bucket  (bucket-name request)
        adapter (get static-content-adapters bucket)
        result  (when adapter
                  (fs/get adapter uri (if-let [version (get-in request [:headers "if-none-match"])]
                                        {:version version}
                                        {})))]
    (cond
      (nil? adapter)              (do (log/warnf "Invalid request to bucket associated with host %s" (get-in request [:headers "host"]))
                                      {:status 404})
      (fs/folder? uri)            (-> {:status 200
                                       :body   result}
                                      (cc/cache-control uri))
      (fs/does-not-exist? result) (not-found)
      (fs/not-modified? result)   (not-modified)
      :else                       (-> {:status  200
                                       :headers {"ETag" (fs/object-version result)}
                                       :body    (fs/object-content result)}
                                      (cc/cache-control uri)))))
