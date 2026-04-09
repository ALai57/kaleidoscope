(ns kaleidoscope.models.s3.get-response
  "Accessors for the cognitect aws-api S3 GetObject response shape.")

(defn etag
  [s3-get-response]
  (:ETag s3-get-response))

(defn metadata
  [s3-get-response]
  {:content-type   (:ContentType s3-get-response)
   :content-length (:ContentLength s3-get-response)
   :user-metadata  (:Metadata s3-get-response)})

(defn content
  [s3-get-response]
  (:Body s3-get-response))

(comment
  ;; Example cognitect aws-api GetObject response shape:
  (def example-get-response
    {:ETag          "\"2b93fa1c4d3a6d105931e75479d3d160\""
     :ContentType   "image/svg"
     :ContentLength 1034
     :Metadata      {"something" "some-value"}
     :Body          #object[java.io.InputStream]}))
