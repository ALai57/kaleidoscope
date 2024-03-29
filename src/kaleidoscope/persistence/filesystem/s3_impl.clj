(ns kaleidoscope.persistence.filesystem.s3-impl
  (:require
   [amazonica.aws.s3 :as s3]
   [amazonica.core :as amazon]
   [clojure.spec.alpha :as s]
   [kaleidoscope.models.s3.get-response :as s3.get]
   [kaleidoscope.models.s3.ls-response :as s3.ls]
   [kaleidoscope.models.s3.put-response :as s3.put]
   [kaleidoscope.persistence.filesystem :as fs]
   [ring.util.mime-type :as mt]
   [steffan-westcott.clj-otel.api.trace.span :as span]
   [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Install multimethod to get resource-data from URLs using S3-PROTOCOL
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

(defn prepare-metadata
  "Format a map of file metadata for upload to S3"
  [{:keys [content-length content-type] :as metadata}]
  (let [user-meta (dissoc metadata :content-length :content-type)]
    (merge {:content-length content-length
            :content-type   content-type}
           (when-not (empty? user-meta) {:user-metadata user-meta}))))

(defn no-such-key?
  [amazon-ex-map]
  (= "NoSuchKey" (:error-code amazon-ex-map)))

(defn get-response->fs-object
  [s3-response]
  (fs/object {:version  (s3.get/etag s3-response)
              :metadata (s3.get/metadata s3-response)
              :content  (s3.get/content s3-response)}))

(defn put-response->fs-object
  [input-stream response]
  (fs/object {:version  (s3.put/etag response)
              :metadata (s3.put/metadata response)
              :content  input-stream}))

(defn ls-response->fs-metadata
  [path result]
  (concat (map (partial s3.ls/summary->file path) (:object-summaries result))
          (map (partial s3.ls/prefix->file path) (:common-prefixes result))))

;; Add wrapper functions that are spec'ed out
(defrecord S3 [bucket]
  fs/DistributedFileSystem
  (ls [_ path options]
    (log/infof "S3 List Objects `%s/%s` with options %s" bucket path options)
    (span/with-span! {:name "kaleidoscope.s3.ls"}
      (let [result (s3/list-objects-v2 {:bucket-name bucket
                                        :prefix      path
                                        :delimiter   s3.ls/FOLDER-DELIMITER})]
        (ls-response->fs-metadata path result))))
  (get-file [_ path options]
    (log/infof "S3 Get Object `%s/%s` with options %s" bucket path options)
    (span/with-span! {:name "kaleidoscope.s3.get"}
      (try
        (if-let [response (s3/get-object (cond-> {:bucket-name bucket
                                                  :key         path}
                                           (:version options) (assoc :nonmatching-e-tag-constraints [(:version options)])))]
          (get-response->fs-object response)
          fs/not-modified-response)
        (catch com.amazonaws.AmazonServiceException e
          (let [ex (amazon/ex->map e)]
            (log/warn "Could not retrieve object" ex)
            (cond
              (no-such-key? ex) fs/does-not-exist-response
              :else             (throw e))))
        (catch com.amazonaws.SdkClientException e
          (log/warn "Could not retrieve object" e))
        (catch Exception e
          (log/warn "Unknown exception" e)))))
  (put-file [this path input-stream metadata]
    (log/infof "S3 Put Object `%s/%s`" bucket path)
    (span/with-span! {:name "kaleidoscope.s3.put"}
      (try
        (let [response (s3/put-object {:bucket-name  bucket
                                       :key          path
                                       :input-stream input-stream
                                       :metadata     (prepare-metadata metadata)})]
          (fs/get this path {:etag (s3.put/etag response)}))
        (catch Exception e
          (log/error "Could not put object" e)
          fs/does-not-exist-response)))))

(defn make-s3
  [{:keys [bucket] :as m}]
  (assoc (map->S3 m)
         :storage-driver "s3"
         :storage-root   bucket))

(comment ;; Playing with S3
  (require '[clojure.java.io :as io])
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  PUT file
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (def b
    (-> (io/resource "public/images/lock.svg")
        io/input-stream
        slurp
        (.getBytes)))

  (fs/put-file (map->S3 {:bucket "andrewslai"})
               "lock.svg"
               (java.io.ByteArrayInputStream. b)
               {:content-type   "image/svg"
                :content-length (count b)
                :something      "some"})

  (def c
    (io/file "resources/public/images/lock.svg"))

  (fs/put-file (map->S3 {:bucket "andrewslai"})
               "lock.svg"
               (io/input-stream c)
               {:content-type   "image/svg"
                :content-length (.length c)
                :something      "some"})


  (s3/put-object {:bucket-name  "andrewslai"
                  :key          "lock.svg"
                  :input-stream (java.io.ByteArrayInputStream. b)
                  :metadata     {:content-type   "image/svg"
                                 :content-length (count b)
                                 :user-metadata  {:something "some-value"}}})

  (s3/get-object {:bucket-name "andrewslai"
                  :key         "lock.svg"})


  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  Just basic play
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (s3/list-buckets)

  (amazon/get-credentials nil)

  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  LIST files
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (fs/get (map->S3 {:bucket "andrewslai-wedding"})
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

  (s3/list-objects-v2 {:bucket-name "andrewslai-wedding"
                       :prefix      "public/"
                       :delimiter   s3.ls/FOLDER-DELIMITER})
  ;; => {:object-summaries
  ;;     [{:key "public/",
  ;;       :size 0,
  ;;       :last-modified #clj-time/date-time "2021-05-27T18:30:07.000Z",
  ;;       :storage-class "STANDARD",
  ;;       :bucket-name "andrewslai-wedding",
  ;;       :etag "d41d8cd98f00b204e9800998ecf8427e"}],
  ;;     :key-count 4,
  ;;     :truncated? false,
  ;;     :delimiter "/",
  ;;     :bucket-name "andrewslai-wedding",
  ;;     :common-prefixes ["public/assets/" "public/css/" "public/images/"],
  ;;     :max-keys 1000,
  ;;     :prefix "public/"}


  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  ;;  GET file
  ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
  (fs/get-file (map->S3 {:bucket "andrewslai-wedding"})
               "index.html"
               {})

  (fs/get (map->S3 {:bucket "andrewslai-wedding"})
          "index.html"
          {:sdk-client-execution-timeout 10000
           :version                      "8ac49aade040081e110a1137cc9f09da"})

  )
