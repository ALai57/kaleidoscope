(ns andrewslai.clj.static-content-test
  (:require [amazonica.aws.s3 :as s3]
            [andrewslai.clj.protocols.s3 :as s3p]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [andrewslai.clj.test-utils :as tu]
            [biiwide.sandboxica.alpha :as sandbox]
            [clojure.test :refer [are deftest is use-fixtures]]
            [matcher-combinators.test]
            [ring.util.response :as response]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest resources-from-classpath-test
  "The Thread's default classloader should be unable to load a resource from a
  location that is not on the classpath. We can make a custom classloader, and
  using that, we can customize the Classpath to load arbitrary files."
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]
    (are [pred loader]
      (pred (response/resource-response path {:loader loader}))

      nil? (.getContextClassLoader (Thread/currentThread))
      map? (tu/make-loader (System/getProperty "java.io.tmpdir")))))

(deftest files-response-test
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (are [pred path options]
      (pred (response/file-response path options))

      nil? (.getName tmpfile) {:root ""}
      map? (.getName tmpfile) {:root (.getAbsolutePath tmpdir)})))

(deftest s3-response-test
  (let [bucket   "andrewslai-wedding"
        endpoint "media/"]
    (sandbox/with (comp (sandbox/just
                         (s3/list-objects-v2
                          ([req]
                           (or (is (match? {:prefix      endpoint
                                            :bucket-name bucket}
                                           req))
                               (throw (Exception. "Invalid inputs")))
                           {:bucket-name     bucket
                            :common-prefixes []
                            :key-count       1}
                           )))
                        sandbox/always-fail)
      (are [expected loader]
        (is (match? expected (response/resource-response endpoint
                                                         {:loader loader})))

        nil?                   (.getContextClassLoader (Thread/currentThread))
        {:status 200 :body ()} (-> {:bucket bucket
                                    :creds  {:profile "none"}}
                                   s3-storage/map->S3
                                   s3p/s3-loader)))))
