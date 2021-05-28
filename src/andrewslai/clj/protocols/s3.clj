(ns andrewslai.clj.protocols.s3
  (:require [andrewslai.clj.persistence.filesystem :as fs]
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
  [s3 url]
  (proxy
      [URLConnection]
      [url]
    (getContent []
      (let [[protocol bucket & more] (string/split (str url) #"/+")
            s (string/join "/" more)]
        (if (folder? url)
          (fs/ls s3 (str s "/"))
          (fs/get-file s3 s))))))

(defn get-bucket-name
  [url]
  (let [[protocol bucket & more] (string/split (str url) #"/+")]
    bucket))

(defn bucket-handler
  [s3]
  (proxy
      [URLStreamHandler]
      []
    (openConnection [url]
      (if (= (:bucket s3) (get-bucket-name url))
        (connection s3 url)
        (throw (IllegalArgumentException.
                (format "Requested resource (%s) not within bucket (%s)" url (:bucket s3))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Creating a classloader that can load resources from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn s3-loader
  [s3]
  (proxy
      [URLClassLoader]
      [(make-array java.net.URL 0)]
    (getResource [s]
      (URL. "s3p" (:bucket s3) -1 (str "/" s) (bucket-handler s3)))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from S3
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmethod ring.util.response/resource-data :s3p
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
        s3-loader))

  (.getResource (s3-loader (s3-storage/map->S3 {:bucket "andrewslai-wedding"
                                                :creds   s3-storage/CustomAWSCredentialsProviderChain}))
                "media/")


  (resp/resource-response "media/" {:loader loader})

  (resp/resource-response "media/rings.jpg" {:loader loader})

  )
