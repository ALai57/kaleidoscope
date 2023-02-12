(ns andrewslai.clj.models.s3.get-response
  "Helpers that understand Amazonica's S3 get-object response.
  Apparently, this is different from the put-response!")

(defn etag
  [s3-get-response]
  (get-in s3-get-response [:object-metadata :etag]))

(defn metadata
  [s3-get-response]
  (get-in s3-get-response [:object-metadata]))

(defn content
  [s3-get-response]
  (get-in s3-get-response [:input-stream]))

(comment
  (require '[data-readers])
  (def example-get-response
    {:bucket-name       "andrewslai",
     :key               "lock.svg",
     :input-stream      "x";;#object[com.amazonaws.services.s3.model.S3ObjectInputStream 0xd053a98 "com.amazonaws.services.s3.model.S3ObjectInputStream@d053a98"],
     :object-content    "x";;#object[com.amazonaws.services.s3.model.S3ObjectInputStream 0xd053a98 "com.amazonaws.services.s3.model.S3ObjectInputStream@d053a98"],
     :redirect-location nil,
     :object-metadata   {:content-disposition                   nil,
                         :expiration-time-rule-id               nil,
                         :user-metadata                         {:something "some-value"},
                         :instance-length                       1034,
                         :version-id                            nil,
                         :server-side-encryption                nil,
                         :server-side-encryption-aws-kms-key-id nil,
                         :etag                                  "2b93fa1c4d3a6d105931e75479d3d160",
                         ;;:last-modified                         #clj-time/date-time "2023-02-11T19:38:58.000Z",
                         :cache-control                         nil,
                         :http-expires-date                     nil,
                         :content-length                        1034,
                         :content-type                          "image/svg",
                         :restore-expiration-time               nil,
                         :content-encoding                      nil,
                         :expiration-time                       nil,
                         :content-md5                           nil,
                         :ongoing-restore                       nil}}))
