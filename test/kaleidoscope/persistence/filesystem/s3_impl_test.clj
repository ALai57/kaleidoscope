(ns kaleidoscope.persistence.filesystem.s3-impl-test
  (:require [clojure.java.io :as io]
            [cognitect.aws.client.api :as aws]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.s3-impl :as sut]
            [kaleidoscope.test-main :as tm]
            [clojure.test :refer [is use-fixtures deftest testing]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level tm/*test-log-level*
      (f))))

(deftest prefixed-key-test
  (testing "prepends a configured prefix to the path"
    (is (= "eph-foo/index.html" (sut/prefixed-key "eph-foo/" "index.html"))))
  (testing "leaves the path unchanged when there is no prefix"
    (is (= "index.html" (sut/prefixed-key nil "index.html")))))

(deftest get-file-and-put-file-apply-the-configured-prefix-test
  (testing "get-file sends the prefixed key to S3, and unset prefix leaves it unchanged"
    (let [requests (atom [])]
      (with-redefs [aws/invoke (fn [_client op-map]
                                 (swap! requests conj op-map)
                                 {:ETag          "\"abc\""
                                  :ContentType   "text/html"
                                  :ContentLength 5
                                  :Body          (io/input-stream (.getBytes "hello"))})]
        (fs/get-file (sut/map->S3 {:bucket "kal-ephemeral" :prefix "eph-foo/" :client :fake-client}) "index.html" {})
        (fs/get-file (sut/map->S3 {:bucket "andrewslai.com" :client :fake-client}) "index.html" {})
        (is (= "eph-foo/index.html" (get-in (first @requests) [:request :Key])))
        (is (= "index.html" (get-in (second @requests) [:request :Key]))))))

  (testing "put-file sends the prefixed key to S3, and unset prefix leaves it unchanged"
    (let [requests (atom [])]
      (with-redefs [aws/invoke (fn [_client op-map]
                                 (swap! requests conj op-map)
                                 ;; Return an anomaly so put-file short-circuits without a follow-up get-file call
                                 {:cognitect.anomalies/category :cognitect.anomalies/fault})]
        (fs/put-file (sut/map->S3 {:bucket "kal-ephemeral" :prefix "eph-foo/" :client :fake-client})
                     "index.html" (io/input-stream (.getBytes "hello")) {})
        (fs/put-file (sut/map->S3 {:bucket "andrewslai.com" :client :fake-client})
                     "index.html" (io/input-stream (.getBytes "hello")) {})
        (is (= "eph-foo/index.html" (get-in (first @requests) [:request :Key])))
        (is (= "index.html" (get-in (second @requests) [:request :Key])))))))

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
