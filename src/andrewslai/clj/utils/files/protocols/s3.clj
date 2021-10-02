(ns andrewslai.clj.protocols.s3
  (:require [andrewslai.clj.utils.files.protocols.core :as protocols]
            [ring.util.response :as ring-response]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using S3-PROTOCOL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def S3-PROTOCOL
  "S3 protocol"
  "s3p")

(defmethod ring-response/resource-data (keyword S3-PROTOCOL)
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))

(comment
  (require '[andrewslai.clj.persistence.s3 :as s3-storage])

  (def loader
    (-> (s3-storage/map->S3 {:bucket "andrewslai-wedding"
                             :creds   s3-storage/CustomAWSCredentialsProviderChain})
        (protocols/filesystem-loader)))

  (.getResource loader "media/")

  (ring-response/resource-response "media/" {:loader loader})

  (ring-response/resource-response "media/rings.jpg" {:loader loader})

  )
