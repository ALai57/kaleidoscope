(ns andrewslai.clj.config-test
  (:require [andrewslai.clj.config :as cfg]
            [andrewslai.clj.protocols.s3 :as s3p]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [deftest is use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest static-content-wrapper--from-classpath
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)

        path (str "/" (.getName tmpfile))

        wrapper (cfg/configure-static-content
                 {"ANDREWSLAI_STATIC_CONTENT"          "classpath"
                  "ANDREWSLAI_STATIC_CONTENT_BASE_URL" (.getName tmpdir)}
                 {:loader tu/tmp-loader})]

    (is (match? {:status  200
                 :headers {"Cache-Control" sc/cache-30d}
                 :body    tu/file?}
                ((wrapper (tu/dummy-app :hello)) {:request-method :get
                                                  :uri            path})))))

(deftest static-content-wrapper--from-filesystem
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)

        path (str "/" (.getName tmpfile))

        wrapper (cfg/configure-static-content
                 {"ANDREWSLAI_STATIC_CONTENT"          "filesystem"
                  "ANDREWSLAI_STATIC_CONTENT_BASE_URL" (.getAbsolutePath tmpdir)})]

    (is (match? {:status  200
                 :headers {"Cache-Control" sc/cache-30d}
                 :body    tu/file?}
                ((wrapper (tu/dummy-app :hello)) {:request-method :get
                                                  :uri            path})))))

;; Makes a network call
#_(deftest static-content-wrapper--from-s3
    (let [wrapper (sc/classpath-static-content-wrapper
                   {:loader (s3p/s3-loader "andrewslai-wedding")
                    :prefer-handler? true})]

      ((wrapper (tu/dummy-app :hello)) {:request-method :get
                                        :uri            "/media/index.html"})))


(comment
  (def tmpdir
    (System/getProperty "java.io.tmpdir"))

  (defn pwd
    []
    (System/getProperty "user.dir"))
  )
