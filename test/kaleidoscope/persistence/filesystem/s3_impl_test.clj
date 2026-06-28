(ns kaleidoscope.persistence.filesystem.s3-impl-test
  (:require [kaleidoscope.persistence.filesystem.s3-impl :as sut]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer [is use-fixtures deftest testing]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(clojure.test/deftest no-such-key-test
  (clojure.test/testing "matches the cognitect AWS SDK error code for missing S3 keys"
    (is (true? (sut/no-such-key? {:cognitect.aws.error/code               "NoSuchKey"
                                  :cognitect.anomalies/category           :cognitect.anomalies/not-found
                                  :cognitect.aws.http/status              404})))
    (is (false? (sut/no-such-key? {:cognitect.aws.error/code              "AccessDenied"
                                   :cognitect.anomalies/category          :cognitect.anomalies/forbidden
                                   :cognitect.aws.http/status             403})))
    (is (false? (sut/no-such-key? {})))))

#_:clj-kondo/ignore
(comment
  (defspec put-object-spec
    (prop/for-all [bucket       gen-s3/gen-bucket-name
                   a-string     gen/string
                   metadata     gen-file/gen-metadata ;; Needs to come from another NS spec, because it's not S3 metadata we're generating
                   user-meta    gen-s3/gen-user-metadata
                   s3-key       gen-s3/gen-key]
      (sandbox/with (comp (sandbox/just
                           (s3/put-object
                            ([req]
                             (or (is (match? {:key          s3-key
                                              :input-stream sut/byte-array-input-stream?
                                              :bucket-name  bucket
                                              :metadata     (sut/prepare-metadata metadata)}
                                             req))
                                 (throw (Exception. "Invalid inputs")))
                             {:bucket-name     bucket
                              :common-prefixes []
                              :key-count       1})))
                          sandbox/always-fail)
                    (fs/put-file (sut/map->S3 {:bucket bucket
                                               :creds  {:profile  "dummy"
                                                        :endpoint "dummy"}})
                                 s3-key
                                 (java.io.ByteArrayInputStream. (.getBytes a-string))
                                 (merge metadata user-meta))))))
