(ns andrewslai.clj.persistence.s3
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.spec.alpha :as s]
            [ring.util.http-response :refer [internal-server-error not-found]]
            [ring.util.mime-type :as mt]
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

(defn valid-key?
  [s]
  (re-matches #"[0-9a-zA-Z/!-_.*'()]+" s))

(defn byte-array-input-stream?
  [obj]
  (= (class obj) java.io.ByteArrayInputStream))

(s/def :s3/bucket-name string?)
(s/def :s3/prefix (s/and string? valid-key?))
(s/def :s3/key (s/and string? valid-key?))
(s/def :s3/input-stream byte-array-input-stream?)

(s/def :s3.summary/size int?)
(s/def :s3.summary/key :s3/key)
(s/def :s3.summary/etag string?)
(s/def :s3.summary/summary
  (s/keys :req-un [:s3.summary/size
                   :s3.summary/key
                   :s3.summary/etag]))
(s/def :s3.summary/summaries
  (s/coll-of :s3.summary/summary))

(s/def :s3.metadata/content-length int?)
(s/def :s3.metadata/content-type (set (vals mt/default-mime-types)))
(s/def :s3.metadata/user-metadata map?)
(s/def :s3.metadata/metadata
  (s/keys :opt-un [:s3.metadata/content-length
                   :s3.metadata/content-type
                   :s3.metadata/user-metadata]))

(defn prepare-metadata
  "Format a map of file metadata for upload to S3"
  [{:keys [content-length content-type] :as metadata}]
  {:content-length content-length
   :content-type   content-type
   :user-metadata  (dissoc metadata :content-length :content-type)})

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
                      :metadata     (prepare-metadata metadata)})
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
                :something "some"})

  (def c
    (clojure.java.io/file "resources/public/images/lock.svg"))

  (fs/put-file (map->S3 {:bucket "andrewslai"
                         :creds CustomAWSCredentialsProviderChain})
               "lock.svg"
               (clojure.java.io/input-stream c)
               {:content-type "image/svg"
                :content-length (.length c)
                :something "some"})

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
