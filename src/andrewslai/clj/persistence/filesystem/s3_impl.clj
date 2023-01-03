(ns andrewslai.clj.persistence.filesystem.s3-impl
  (:require [amazonica.aws.s3 :as s3]
            [amazonica.core :as amazon]
            [andrewslai.clj.persistence.filesystem :as fs]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [ring.util.http-response :refer [internal-server-error not-found]]
            [ring.util.mime-type :as mt]
            [ring.util.response :as ring-response]
            [taoensso.timbre :as log])
  (:import
   [com.amazonaws.auth AWSCredentialsProviderChain ContainerCredentialsProvider EnvironmentVariableCredentialsProvider]
   com.amazonaws.auth.profile.ProfileCredentialsProvider))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using S3-PROTOCOL
;; Useful for teaching http-mw how to retrieve static assets from a FS
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def S3-PROTOCOL
  "S3 protocol"
  "s3p")

(defmethod ring-response/resource-data (keyword S3-PROTOCOL)
  [url]
  (let [conn (.openConnection url)]
    {:content (.getContent url)}))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using S3-PROTOCOL
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; TODO: Is this custom provider necesssary?
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
(s/def :s3.metadata/user-metadata (s/map-of string? any?))
(s/def :s3.metadata/metadata
  (s/keys :opt-un [:s3.metadata/content-length
                   :s3.metadata/content-type
                   :s3.metadata/user-metadata]))

(defn prepare-metadata
  "Format a map of file metadata for upload to S3"
  [{:keys [content-length content-type] :as metadata}]
  (let [user-meta (dissoc metadata :content-length :content-type)]
    (merge {:content-length content-length
            :content-type   content-type}
           (when-not (empty? user-meta) {:user-metadata user-meta}))))

(def FOLDER-DELIMITER "/")

(defn folder?
  ([s]
   (folder? s FOLDER-DELIMITER))
  ([s delim]
   (= delim (str (last s)))))

(defn extract-name
  [path s]
  (let [x (last (string/split s (re-pattern FOLDER-DELIMITER)))]
    (cond
      (= path s)  ""
      (folder? s) (str x FOLDER-DELIMITER)
      :else       x)))

(defn summary->file
  [path {:keys [key] :as summary}]
  (assoc summary
         :name (extract-name path key)
         :path key
         :type (if (folder? key)
                 :directory
                 :file)))

(defn prefix->file
  [path prefix]
  {:name (extract-name path prefix)
   :path prefix
   :type :directory})

;; Add wrapper functions that are spec'ed out
(defrecord S3 [bucket creds protocol]
  fs/FileSystem
  (ls [_ path]
    (log/infof "S3 List Objects `%s/%s`" bucket path)
    (let [result (s3/list-objects-v2 creds
                                     {:bucket-name bucket
                                      :prefix      path
                                      :delimiter   FOLDER-DELIMITER})]
      (:object-summaries result)
      (concat (map (partial summary->file path) (:object-summaries result))
              (map (partial prefix->file path) (:common-prefixes result)))))
  (get-file [_ path]
    (log/infof "S3 Get Object `%s/%s`" bucket path)
    (try
      (-> (s3/get-object creds {:bucket-name bucket :key path})
          :input-stream)
      (catch Exception e
        (log/warn "Could not retrieve object" (amazon/ex->map e))
        (exception-response (amazon/ex->map e)))))
  (put-file [_ path input-stream metadata]
    (log/infof "S3 Put Object `%s/%s`" bucket path)
    (try
      (s3/put-object creds
                     {:bucket-name  bucket
                      :key          path
                      :input-stream input-stream
                      :metadata     (prepare-metadata metadata)})
      (catch Exception e
        (log/error "Could not put object" e)
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
  (fs/get (map->S3 {:bucket "andrewslai-wedding"
                    :creds CustomAWSCredentialsProviderChain})
          "public/")
  ;; => ({:path "public/css/",
  ;;      :key "public/css/",
  ;;      :storage-class "STANDARD",
  ;;      :name "",
  ;;      :type :directory,
  ;;      :etag "d41d8cd98f00b204e9800998ecf8427e",
  ;;      :last-modified #clj-time/date-time "2021-05-27T18:30:39.000Z",
  ;;      :size 0,
  ;;      :bucket-name "andrewslai-wedding"}
  ;;     {:path "public/css/wedding.css",
  ;;      :key "public/css/wedding.css",
  ;;      :storage-class "STANDARD",
  ;;      :name "wedding.css",
  ;;      :type :file,
  ;;      :etag "71bd22a749b50d4a5b90a8f538b72a60",
  ;;      :last-modified #clj-time/date-time "2021-05-27T18:33:10.000Z",
  ;;      :size 1121,
  ;;      :bucket-name "andrewslai-wedding"})

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  GET file
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (fs/get-file (map->S3 {:bucket "andrewslai-wedding"
                         :creds CustomAWSCredentialsProviderChain})
               "index.html")
  ;; => #object[com.amazonaws.services.s3.model.S3ObjectInputStream 0x2fdbe8b5 "com.amazonaws.services.s3.model.S3ObjectInputStream@2fdbe8b5"]

  )

(comment

  ;; Http MW loading static content
  (def loader
    (-> (map->S3 {:bucket "andrewslai-wedding"
                  :creds   CustomAWSCredentialsProviderChain})
        (protocols/filesystem-loader)))

  (.getResource loader "media/")

  (ring-response/resource-response "media/" {:loader loader})

  (ring-response/resource-response "media/rings.jpg" {:loader loader})

  )
