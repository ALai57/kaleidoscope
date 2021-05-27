(ns andrewslai.clj.protocols.s3
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.string :as string])
  (:import [java.net URLConnection URLStreamHandler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Teaching java.net.URL how to interpret the s3p protocol
;; This installs a new URLStreamHandler so that URLs know how to use s3p protocol
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
      (let [[protocol bucket & more] (string/split (str url) #"/")
            s (string/join "/" more)]
        (if (folder? url)
          (fs/ls filesystem (str s "/"))
          (fs/get-file filesystem s))))))

(defn select-connection
  [bucket url]
  (string/starts-with? url (format "s3p:/%s" bucket)))

(defn get-bucket-name
  [url]
  (let [[protocol bucket & more] (string/split (str url) #"/")]
    bucket))

(defn stream-handler
  [connected-buckets]
  (proxy
      [URLStreamHandler]
      []
    (openConnection [url]
      (if-let [s3-connection (get connected-buckets (get-bucket-name url))]
        (connection s3-connection url)
        (throw (IllegalArgumentException.
                (format "Requested resource (%s) not within buckets (%s)" url (vals connected-buckets))))))))
