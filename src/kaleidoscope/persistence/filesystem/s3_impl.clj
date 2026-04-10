(ns kaleidoscope.persistence.filesystem.s3-impl
  (:require
   [clojure.java.io :as io]
   [clojure.spec.alpha :as s]
   [cognitect.aws.client.api :as aws]
   [kaleidoscope.models.s3.get-response :as s3.get]
   [kaleidoscope.models.s3.ls-response :as s3.ls]
   [kaleidoscope.models.s3.put-response :as s3.put]
   [kaleidoscope.persistence.filesystem :as fs]
   [ring.util.mime-type :as mt]
   [steffan-westcott.clj-otel.api.trace.span :as span]
   [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Specs
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

;; Lazily created — only instantiated when S3 is actually used.
;; Tests that use the in-memory filesystem never trigger client creation.
(defonce ^:private s3-client
  (delay (aws/client {:api :s3})))

(def ^:private S3-TIMEOUT-MS 10000)

(defn- invoke-with-timeout
  "Calls aws/invoke on a background thread and waits up to S3-TIMEOUT-MS.
  Logs and throws ex-info if the deadline is exceeded."
  [client op-map]
  (let [fut    (future (aws/invoke client op-map))
        result (deref fut S3-TIMEOUT-MS ::timeout)]
    (if (= ::timeout result)
      (do (future-cancel fut)
          (log/errorf "S3 %s timed out after %dms — request: %s"
                      (:op op-map) S3-TIMEOUT-MS (:request op-map))
          (throw (ex-info "S3 operation timed out"
                          {:op         (:op op-map)
                           :request    (:request op-map)
                           :timeout-ms S3-TIMEOUT-MS})))
      result)))

(defn prepare-metadata
  "Build the PutObject request fields for content-type, content-length, and
  user-defined metadata from a Ring-style metadata map."
  [{:keys [content-length content-type] :as metadata}]
  (let [user-meta (-> (dissoc metadata :content-length :content-type)
                      (update-keys #(if (keyword? %) (name %) (str %)))
                      (update-vals str))]
    (cond-> {}
      content-type    (assoc :ContentType content-type)
      content-length  (assoc :ContentLength content-length)
      (seq user-meta) (assoc :Metadata user-meta))))

(defn no-such-key?
  [result]
  (= "NoSuchKey" (:__type result)))

(defn copy-input-stream
  "Copy the S3 response stream into a byte array so the HTTP connection can
  be released immediately."
  [input-stream]
  (span/with-span! {:name "kaleidoscope.s3.get.convert-input-stream"}
    (let [baos (java.io.ByteArrayOutputStream.)]
      (io/copy input-stream baos)
      (.close input-stream)
      (io/input-stream (.toByteArray baos)))))

(defn get-response->fs-object
  [s3-response]
  (let [s3-input-stream (s3.get/content s3-response)]
    (fs/object {:version  (s3.get/etag s3-response)
                :metadata (s3.get/metadata s3-response)
                :content  (copy-input-stream s3-input-stream)})))

(defn put-response->fs-object
  [input-stream response]
  (fs/object {:version (s3.put/etag response)
              :content input-stream}))

(defn ls-response->fs-metadata
  [path result]
  (concat (map (partial s3.ls/summary->file path) (:Contents result))
          (map (partial s3.ls/prefix->file path) (:CommonPrefixes result))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; S3 filesystem record
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defrecord S3 [bucket]
  fs/DistributedFileSystem
  (ls [_ path options]
    (log/infof "S3 List Objects `%s/%s` with options %s" bucket path options)
    (span/with-span! {:name "kaleidoscope.s3.ls"}
      (let [result (invoke-with-timeout @s3-client
                                        {:op      :ListObjectsV2
                                         :request {:Bucket    bucket
                                                   :Prefix    path
                                                   :Delimiter s3.ls/FOLDER-DELIMITER}})]
        (ls-response->fs-metadata path result))))

  (get-file [_ path options]
    (log/infof "S3 Get Object `%s/%s` with options %s" bucket path options)
    (span/with-span! {:name "kaleidoscope.s3.get"}
      (let [result (invoke-with-timeout @s3-client
                                        {:op      :GetObject
                                         :request (cond-> {:Bucket bucket :Key path}
                                                    (:version options) (assoc :IfNoneMatch (:version options)))})]
        (cond
          (= 304 (:http-status result))          fs/not-modified-response
          (no-such-key? result)                  (do (log/warn "Object not found" result)
                                                     fs/does-not-exist-response)
          (:cognitect.anomalies/category result) (do (log/errorf "S3 get-file anomaly for `%s/%s`: %s" bucket path result)
                                                     (throw (ex-info "S3 get-file error" result)))
          :else                                  (get-response->fs-object result)))))

  (put-file [this path input-stream metadata]
    (log/infof "S3 Put Object `%s/%s`" bucket path)
    (span/with-span! {:name "kaleidoscope.s3.put"}
      (let [result (invoke-with-timeout @s3-client
                                        {:op      :PutObject
                                         :request (merge {:Bucket bucket :Key path :Body input-stream}
                                                         (prepare-metadata metadata))})]
        (if (:cognitect.anomalies/category result)
          (do (log/error "Could not put object" result)
              fs/does-not-exist-response)
          (fs/get this path {:etag (s3.put/etag result)}))))))

(defn make-s3
  [{:keys [bucket] :as m}]
  (assoc (map->S3 m)
         :storage-driver "s3"
         :storage-root   bucket))

(comment ;; Playing with S3
  (require '[clojure.java.io :as io])

  (def b
    (-> (io/resource "public/images/lock.svg")
        io/input-stream
        slurp
        (.getBytes)))

  (fs/put-file (make-s3 {:bucket "andrewslai"})
               "lock.svg"
               (java.io.ByteArrayInputStream. b)
               {:content-type   "image/svg"
                :content-length (count b)
                :something      "some"})

  ;; List objects
  (aws/invoke @s3-client {:op      :ListObjectsV2
                          :request {:Bucket    "andrewslai-wedding"
                                    :Prefix    "public/"
                                    :Delimiter s3.ls/FOLDER-DELIMITER}})

  ;; Get object
  (aws/invoke @s3-client {:op      :GetObject
                          :request {:Bucket "andrewslai.com"
                                    :Key    "static/images/splash-logo.svg"}})

  ;; List buckets (verify credentials)
  (aws/invoke @s3-client {:op :ListBuckets})
  )
