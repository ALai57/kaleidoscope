(ns andrewslai.clj.protocols.s3
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [clojure.string :as string])
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
      (let [[protocol bucket & more] (string/split (str url) #"/+")
            s (string/join "/" more)]
        (if (folder? url)
          (fs/ls filesystem (str s "/"))
          (fs/get-file filesystem s))))))

(defn get-bucket-name
  [url]
  (let [[protocol bucket & more] (string/split (str url) #"/+")]
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


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creating a classloader that can load resources from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn s3-loader
  [bucket-name stream-handler]
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [s]
      (URL. "s3p" bucket-name -1 (str "/" s) stream-handler))))

(defn s3-connections
  [buckets creds]
  (reduce (fn [m bucket]
            (conj m {bucket (s3-storage/map->S3 {:bucket bucket
                                                 :creds creds})}))
          {}
          buckets))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod ring.util.response/resource-data :s3p
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))



(comment
  (require '[andrewslai.clj.persistence.s3 :as s3x])
  (require '[ring.util.response :as resp])

  (def connections
    (s3-connections ["andrewslai-wedding"]
                    s3-storage/CustomAWSCredentialsProviderChain))

  (.getResource (s3-loader "andrewslai-wedding" (stream-handler connections))
                "media/")


  (resp/resource-response "media/"
                          {:loader (s3-loader "andrewslai-wedding"
                                              (stream-handler connections))})

  (resp/resource-response "media/rings.jpg"
                          {:loader (s3-loader "andrewslai-wedding"
                                              (stream-handler connections))})

  )
