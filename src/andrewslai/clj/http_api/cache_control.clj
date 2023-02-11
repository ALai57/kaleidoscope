(ns andrewslai.clj.http-api.cache-control
  (:require [ring.util.http-predicates :refer [success?]]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Cache control helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def no-cache  "max-age=0,no-cache,no-store")
(def cache-30d "public,max-age=2592000,s-maxage=2592000")
(def cache-10d "public,max-age=864000,s-maxage=864000")

(def url-caching-policy
  [[#"\.png$" cache-30d]
   [#"\.jpg$" cache-30d]
   [#"\.svg$" cache-30d]
   [#"\.css$" cache-10d]
   ])

(defn find-first-match
  "Searches through a collection of potential matches to find the first matching
  regex and return the associated value."
  [potential-matches s default-val]
  (reduce (fn [default [regexp v]]
            (if (re-find regexp s)
              (reduced v)
              default))
          default-val
          potential-matches))

(defn cache-control-header
  "Find the correct cache control headers for a given URL.
  Useful because we are serving static content from S3 - so some content should have
  long caching (images) while others should not (actual site)."
  [url]
  (find-first-match url-caching-policy url no-cache))

(defn cache-control
  "Add Cache Control Headers for successful responses"
  [url response]
  (if (success? response)
    (assoc-in response
              [:headers "Cache-Control"]
              (cache-control-header url))
    response))
