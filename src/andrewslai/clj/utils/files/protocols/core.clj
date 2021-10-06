(ns andrewslai.clj.utils.files.protocols.core
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.utils.files.protocols.core :as protocols]
            [clojure.string :as string]
            [ring.util.response :as ring-response])
  (:import [java.net URL URLClassLoader URLConnection URLStreamHandler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Teaching java.net.URL how to interpret the s3p protocol
;; Can be used to create a new URLStreamHandler so that URLs know how to use s3p protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn folder?
  [url]
  (string/ends-with? (str url) "/"))

(defn connection
  [filesystem url]
  (proxy
      [URLConnection]
      [url]
    (getContent []
      (let [[protocol & more] (string/split (str url) #"/+")
            s (string/join "/" more)]
        (if (folder? url)
          (fs/ls filesystem (str s "/"))
          (fs/get-file filesystem s))))))

(defn url-stream-handler
  [filesystem]
  (proxy
      [URLStreamHandler]
      []
    (openConnection [url]
      (connection filesystem url))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creating a classloader that can load resources from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn make-url
  [protocol host port path stream-handler]
  (URL. protocol "" -1 (str "/" path) stream-handler))

;; Don't pass protocol - create a method on FileSystem to describe protocol
(defn filesystem-loader
  [filesystem]
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [path]
      (make-url (fs/get-protocol filesystem) "" -1 (str "/" path) (url-stream-handler filesystem)))))