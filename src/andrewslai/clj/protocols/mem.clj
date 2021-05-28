(ns andrewslai.clj.protocols.mem
  (:require [ring.util.response])
  (:import [java.net URLConnection URLStreamHandler URLStreamHandlerFactory URLClassLoader URL]))

(defn connection
  [mem-filesystem url]
  (proxy
      [URLConnection]
      [url]
    (getContent []
      (get @mem-filesystem (str url)))))

(defn stream-handler
  [mem-filesystem]
  (proxy
      [URLStreamHandler]
      []
    (openConnection [url]
      (connection mem-filesystem url))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creating a classloader that can load resources from an in-memory filesystem atom
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn loader
  [stream-handler]
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [s]
      (URL. "mem" "" -1 (str "/" s) stream-handler))))

(defmethod ring.util.response/resource-data :mem
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))
