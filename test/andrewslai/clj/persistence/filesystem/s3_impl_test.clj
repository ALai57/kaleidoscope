(ns andrewslai.clj.persistence.filesystem.s3-impl-test
  (:require [amazonica.aws.s3 :as s3]
            [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.persistence.filesystem.s3-impl :refer :all]
            [andrewslai.generators.files :as gen-file]
            [andrewslai.generators.s3 :as gen-s3]
            [biiwide.sandboxica.alpha :as sandbox]
            [clojure.test :refer [is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(defspec put-object-spec
  (prop/for-all [bucket       gen-s3/gen-bucket-name
                 a-string     gen/string
                 metadata     gen-file/gen-metadata ;; Needs to come from another NS spec, because it's not S3 metadata we're generating
                 user-meta    gen-s3/gen-user-metadata
                 s3-key       gen-s3/gen-key]
    (sandbox/with (comp (sandbox/just
                          (s3/put-object
                            ([req]
                             (or (is (match? {:key s3-key
                                              :input-stream byte-array-input-stream?
                                              :bucket-name  bucket
                                              :metadata     (prepare-metadata metadata)}
                                             req))
                                 (throw (Exception. "Invalid inputs")))
                             {:bucket-name     bucket
                              :common-prefixes []
                              :key-count       1})))
                        sandbox/always-fail)
                  (fs/put-file (map->S3 {:bucket bucket
                                         :creds  {:profile "dummy"
                                                  :endpoint "dummy"}})
                               s3-key
                               (java.io.ByteArrayInputStream. (.getBytes a-string))
                               (merge metadata user-meta)))))
