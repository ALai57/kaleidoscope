(ns kaleidoscope.tasks.planner-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.tasks.planner :as planner]))

(deftest anthropic-http-client-has-a-connect-timeout-test
  (testing "A stalled TCP connect to Anthropic can't hang the calling thread forever"
    (is (= (java.time.Duration/ofSeconds 10)
           (.orElse (.connectTimeout @@#'planner/anthropic-http-client) nil)))))
