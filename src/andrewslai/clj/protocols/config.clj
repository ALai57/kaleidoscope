(ns andrewslai.clj.protocols.config
  (:require [andrewslai.clj.protocols.mem :as memp]
            [andrewslai.clj.protocols.s3 :as s3p]
            [andrewslai.clj.persistence.s3 :refer [s3-connections]])
  (:import [java.net URL URLStreamHandlerFactory]))

(defn stream-handler
  [protocol-map]
  (proxy
      [URLStreamHandlerFactory]
      []
    (createURLStreamHandler [s]
      (get protocol-map s))))

(defn install-protocols!
  [protocol-map]
  (try
    (URL/setURLStreamHandlerFactory (stream-handler protocol-map))
    (println "Adding S3 protocol handler (s3p:/)")
    (println "Adding mem protocol handler (mem:/)")
    (catch Throwable e
      (println "Already installed protocols! Cannot install twice"))))
