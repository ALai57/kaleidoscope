(ns andrewslai.clj.config-test
  (:require [andrewslai.clj.config :as cfg]
            [andrewslai.clj.utils.files.protocols.s3 :as s3p]
            [andrewslai.clj.http-api.static-content :as sc]
            [andrewslai.clj.test-utils :as tu]
            [clojure.test :refer [deftest is use-fixtures]]
            [matcher-combinators.test]
            [taoensso.timbre :as log]
            [andrewslai.clj.http-api.wedding :as wedding]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

(deftest config-from-env-test
  (is (= wedding/access-rules
         (get-in (cfg/configure-from-env {})
                 [:wedding :access-rules]))))

;; Makes a network call
#_(deftest static-content-wrapper--from-s3
    (let [wrapper (sc/classpath-static-content-wrapper
                   {:loader (protocols/filesystem-loader "andrewslai-wedding")
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
