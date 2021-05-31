(ns andrewslai.clj.persistence.s3
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.persistence.filesystem :as fs]
            [ring.util.http-response :refer [internal-server-error not-found]]
            [taoensso.timbre :as log])
  (:import [com.amazonaws.auth AWSCredentialsProviderChain ContainerCredentialsProvider EnvironmentVariableCredentialsProvider]
           com.amazonaws.auth.profile.ProfileCredentialsProvider))

(def CustomAWSCredentialsProviderChain
  (AWSCredentialsProviderChain.
   [^AWSCredentialsProvider (new EnvironmentVariableCredentialsProvider)
    ^AWSCredentialsProvider (new ProfileCredentialsProvider)
    ^AWSCredentialsProvider (new ContainerCredentialsProvider)]))

(defn exception-response
  [{:keys [status-code] :as exception-map}]
  (case status-code
    404 (not-found)
    (internal-server-error "Unknown exception")))

(defrecord S3 [bucket creds]
  fs/FileSystem
  (ls [_ path]
    (log/info "List objects in S3: " path)
    (->> (s3/list-objects-v2 creds
                             {:bucket-name bucket
                              :prefix      path})
         :object-summaries
         (drop 1)
         (map (fn [m] (select-keys m [:key :size :etag])))
         seq))
  (get-file [_ path]
    (log/info "Get object in S3: " path)
    (try
      (-> (s3/get-object creds {:bucket-name bucket :key path})
          :input-stream)
      (catch Exception e
        (println e)
        (exception-response (amazon/ex->map e)))))
  (put-file [_ path input-stream metadata]
    (log/info "Put object in S3: " path)
    (try

      (s3/put-object creds
                     {:bucket-name  bucket
                      :key          path
                      :input-stream input-stream
                      :metadata     metadata})
      (catch Exception e
        (println e)
        (exception-response (amazon/ex->map e))))))


(comment ;; Playing with S3

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  PUT file
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (def b
    (-> (clojure.java.io/resource "public/images/lock.svg")
        clojure.java.io/input-stream
        slurp
        (.getBytes)))

  (fs/put-file (map->S3 {:bucket "andrewslai"
                         :creds CustomAWSCredentialsProviderChain})
               "lock.svg"
               (java.io.ByteArrayInputStream. b)
               {:content-type "image/svg"
                :content-length (count b)
                :user-metadata {:something "some"}})

  (s3/put-object CustomAWSCredentialsProviderChain
                 {:bucket-name  "andrewslai"
                  :key          "lock.svg"
                  :input-stream (java.io.ByteArrayInputStream. b)
                  :metadata     {:content-type "image/svg"
                                 :content-length (count b)
                                 :user-metadata {:something "some-value"}}})

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  Just basic play
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (s3/list-buckets)

  (amazon/get-credentials nil)

  (keys (bean (.getCredentials CustomAWSCredentialsProviderChain)))

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  LIST files
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (fs/ls (map->S3 {:bucket "andrewslai-wedding"
                   :creds CustomAWSCredentialsProviderChain})
         "media/")


  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  GET file
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (fs/get-file (map->S3 {:bucket "andrewslai-wedding"
                         :creds CustomAWSCredentialsProviderChain})
               "index.html")

  )
