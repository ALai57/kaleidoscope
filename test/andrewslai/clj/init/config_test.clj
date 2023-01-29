(ns andrewslai.clj.init.config-test
  (:require [clojure.test :refer :all]
            [andrewslai.clj.test-main :as tm]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-log-level tm/*test-log-level*
      (f))))

(comment
  (def tmpdir
    (System/getProperty "java.io.tmpdir"))

  (defn pwd
    []
    (System/getProperty "user.dir"))
  )
