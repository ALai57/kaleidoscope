(ns andrewslai.clj.static-content-test
  (:require [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [are deftest is use-fixtures]]
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

(comment
  (def tmpdir
    (System/getProperty "java.io.tmpdir"))

  (defn pwd
    []
    (System/getProperty "user.dir"))
  )
