(ns andrewslai.clj.config-test
  (:require [andrewslai.clj.handler :as h]
            [andrewslai.clj.static-content :as sc]
            [andrewslai.clj.config :as cfg]
            [andrewslai.clj.test-utils :as tu]
            [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [clojure.string :as string]
            [clojure.test :refer [are deftest is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.properties :as prop]
            [compojure.api.sweet :refer [api defroutes GET routes]]
            [matcher-combinators.test]
            [ring.util.request :as req]
            [ring.util.response :as response]
            [taoensso.timbre :as log])
  (:import [java.io File]
           [java.net URL URLClassLoader]
           [java.nio.file Files]
           [java.nio.file.attribute PosixFilePermissions]
           [java.nio.file.attribute FileAttribute]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest static-content-wrapper--from-classpath
  (let [tmpdir  (tu/mktmpdir "andrewslai-test")
        tmpfile (tu/mktmp "delete.txt" tmpdir)

        path    (str "/" (.getName tmpfile))

        wrapper (cfg/configure-static-content
                 {"ANDREWSLAI_STATIC_CONTENT" "classpath"
                  "ANDREWSLAI_STATIC_CONTENT_BASE_URL" (.getName tmpdir)}
                 {:loader tu/tmp-loader})]

    (is (some? ((wrapper (tu/dummy-app :hello)) {:request-method :get
                                                 :uri            path})))))

(comment
  (def tmpdir
    (System/getProperty "java.io.tmpdir"))

  (defn pwd
    []
    (System/getProperty "user.dir"))
  )
