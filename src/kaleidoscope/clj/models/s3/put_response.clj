(ns kaleidoscope.clj.models.s3.put-response
  "Helpers that understand Amazonica's S3 put-object response.
  Apparently, this is different from the get-response!")

(defn etag
  [put-response]
  (:etag put-response))

(defn metadata
  [put-response]
  (:metadata put-response))

(comment
  (def example-response
    {:requester-charged? false,
     :content-md5        "K5P6HE06bRBZMedUedPRYA==",
     :etag               "2b93fa1c4d3a6d105931e75479d3d160"
     :metadata           {:content-disposition                   nil,
                          :expiration-time-rule-id               nil,
                          :user-metadata                         nil,
                          :instance-length                       0,
                          :version-id                            nil,
                          :server-side-encryption                nil,
                          :server-side-encryption-aws-kms-key-id nil,
                          :etag                                  "2b93fa1c4d3a6d105931e75479d3d160",
                          :last-modified                         nil,
                          :cache-control                         nil,
                          :http-expires-date                     nil,
                          :content-length                        0,
                          :content-type                          nil,
                          :restore-expiration-time               nil,
                          :content-encoding                      nil,
                          :expiration-time                       nil,
                          :content-md5                           nil,
                          :ongoing-restore                       nil},}

    ))
