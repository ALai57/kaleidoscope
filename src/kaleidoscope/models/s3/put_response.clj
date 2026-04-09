(ns kaleidoscope.models.s3.put-response
  "Accessors for the cognitect aws-api S3 PutObject response shape.")

(defn etag
  [put-response]
  (:ETag put-response))

(comment
  ;; Example cognitect aws-api PutObject response shape:
  (def example-response
    {:ETag "\"2b93fa1c4d3a6d105931e75479d3d160\""}))
