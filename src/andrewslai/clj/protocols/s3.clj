(ns andrewslai.clj.protocols.s3
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.protocols.core :as protocols]
            [clojure.string :as string]
            [ring.util.response :as ring-resp])
  (:import [java.net URL URLClassLoader URLConnection URLStreamHandler]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Teaching java.net.URL how to interpret the s3p protocol
;; Can be used to create a new URLStreamHandler so that URLs know how to use s3p protocol
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def S3-PROTOCOL
  "s3p")

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

(defn stream-handler
  [filesystem]
  (proxy
      [URLStreamHandler]
      []
    (openConnection [url]
      (connection filesystem url))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creating a classloader that can load resources from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn filesystem-loader
  [filesystem]
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [path]
      (URL. S3-PROTOCOL "" -1 (str "/" path) (stream-handler filesystem)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using S3-PROTOCOL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod ring-resp/resource-data (keyword S3-PROTOCOL)
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))



(comment
  (require '[andrewslai.clj.persistence.s3 :as s3-storage])
  (require '[ring.util.response :as resp])

  (def loader
    (-> {:bucket "andrewslai-wedding"
         :creds   s3-storage/CustomAWSCredentialsProviderChain}
        s3-storage/map->S3
        filesystem-loader))

  (.getResource (filesystem-loader (s3-storage/map->S3 {:bucket "andrewslai-wedding"
                                                        :creds   s3-storage/CustomAWSCredentialsProviderChain}))
                "media/")


  (resp/resource-response "media/" {:loader loader})

  (resp/resource-response "media/rings.jpg" {:loader loader})

  )
