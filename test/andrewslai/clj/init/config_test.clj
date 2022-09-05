(ns andrewslai.clj.init.config-test
  (:require [clojure.test :refer :all]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level :fatal
      (f))))

;; Makes a network call
#_(deftest static-content-wrapper--from-s3
    (let [wrapper (sc/classpath-static-content-stack
                   ""
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
