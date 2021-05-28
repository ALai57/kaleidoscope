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

    (is (nil? (.getResource (.getContextClassLoader (Thread/currentThread)) path)))
    (is (some? (.getResource tu/tmp-loader path)))))

(deftest resources-from-classpath-test
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (are [pred loader]
      (pred (response/resource-response path {:loader loader}))

      nil? (.getContextClassLoader (Thread/currentThread))
      map? tu/tmp-loader)))

(deftest files-response-test
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (are [pred path options]
      (pred (response/file-response path options))

      nil? (.getName tmpfile) {:root ""}
      map? (.getName tmpfile) {:root (.getAbsolutePath tmpdir)})))

(deftest s3-response-test
  (let [bucket "andrewslai-wedding"
        endpoint "media/"]
    (sandbox/with (comp (sandbox/just
                         (s3/list-objects-v2
                          ([req]
                           (or (is (match? {:prefix endpoint
                                            :bucket-name bucket}
                                           req))
                               (throw (Exception. "Invalid inputs")))
                           {:bucket-name bucket
                            :common-prefixes []
                            :key-count 1}
                           )))
                        sandbox/always-fail)
      (are [expected loader]
        (is (match? expected (response/resource-response endpoint
                                                         {:loader loader})))

        nil? (.getContextClassLoader (Thread/currentThread))
        {:status 200 :body nil} (-> {:bucket bucket
                                     :creds  {:profile "none"}}
                                    s3-storage/map->S3
                                    s3p/s3-loader)))))

(comment
  (def tmpdir
    (System/getProperty "java.io.tmpdir"))

  (defn pwd
    []
    (System/getProperty "user.dir"))

  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)
        path    (str (.getName tmpdir) "/" (.getName tmpfile))]

    (is (nil? (.getResource (.getContextClassLoader (Thread/currentThread)) path)))
    (is (some? (.getResource tu/tmp-loader path))))



  (let [bucket "andrewslai-wedding"
        endpoint "media/"]
    (sandbox/with (comp (sandbox/just
                         (s3/list-objects-v2
                          ([req]
                           (println req)
                           (or (is (match? {:bucket-name bucket
                                            :prefix endpoint}
                                           req))
                               (throw (Exception. "Invalid inputs")))

                           (println "RETURN")

                           {:common-prefixes [{:prefix "x/"}]
                            :contents {:e-tag "w"
                                       :key "x"
                                       :last-modified (java.time.Instant/now)
                                       :owner {:display-name "hello"
                                               :id "something"}
                                       :size  10
                                       :storage-class "something"}
                            :delimiter "hello"
                            :encoding-type "utf-8"
                            :is-truncated false
                            :key-count 1
                            :max-keys 10
                            :name bucket
                            :prefix "hello"}
                           #_{:content-type "something"
                              :response-metadata {:request-id "he"}}
                           #_{:content-type "something"
                              :response-metadata {:request-id "he"}})))
                        sandbox/always-nothing)
      (response/resource-response (format "s3p:/%s/%s" bucket endpoint)
                                  {:loader (s3p/s3-loader)})))


  )
