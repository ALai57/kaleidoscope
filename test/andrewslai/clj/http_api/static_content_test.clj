(ns andrewslai.clj.http-api.static-content-test
  (:require [amazonica.aws.s3 :as s3]
            [andrewslai.clj.utils.files.protocols.core :as protocols]
            [andrewslai.clj.persistence.s3 :as s3-storage]
            [andrewslai.clj.utils.files.protocols.mem :as memp]
            [andrewslai.clj.test-utils :as tu]
            [andrewslai.clj.http-api.static-content :as sc]
            [biiwide.sandboxica.alpha :as sandbox]
            [clojure.test :refer [are deftest is use-fixtures]]
            [matcher-combinators.test]
            [ring.util.response :as response]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest cache-control-header-test
  (is (= sc/no-cache (sc/cache-control-header "Hello.html")))
  (is (= sc/cache-30d (sc/cache-control-header "Hello.html.")))
  (is (= sc/no-cache (sc/cache-control-header "hello/")))
  (is (= sc/cache-30d (sc/cache-control-header "hello/htm"))))

(deftest cache-control-with-success-response-test
  (are [expected url]
    (= {:status  200
        :headers {"Cache-Control" expected}}
       (sc/cache-control url {:status 200}))

    sc/no-cache  "hello.html"
    sc/no-cache  "hello/"
    sc/cache-30d "hello/htm"
    ))

(deftest cache-control-with-non-200-test
  (is (= {:status 400}
         (sc/cache-control "Hello.html" {:status 400}))))

(deftest resources-from-classpath-test
  "The Thread's default classloader should be unable to load a resource from a
  location that is not on the classpath. We can make a custom classloader, and
  using that, we can customize the Classpath to load arbitrary files."
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]
    (are [pred opts]
      (pred (response/resource-response path opts))

      nil? {:loader (.getContextClassLoader (Thread/currentThread))}
      map? {:loader (tu/make-loader tu/TEMP-DIRECTORY)})))

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
                    {:status 200 :body ()} (->> (s3-storage/map->S3 {:bucket bucket
                                                                     :creds  {:profile "none"
                                                                              :endpoint "dummy"}})
                                                (protocols/filesystem-loader))))))

(comment
  (let [bucket   "andrewslai-wedding"
        endpoint "media/index.html"]
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
                  (response/resource-response endpoint
                                              {:loader (-> (s3-storage/map->S3 {:bucket bucket
                                                                                :creds  {:profile  "none"
                                                                                         :endpoint "dummy"}})
                                                           (protocols/filesystem-loader))})))

  )
