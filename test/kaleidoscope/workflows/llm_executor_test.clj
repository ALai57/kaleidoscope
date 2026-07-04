(ns kaleidoscope.workflows.llm-executor-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.workflows.llm-executor :as llm-executor]))

(deftest anthropic-http-client-has-a-connect-timeout-test
  (testing "A stalled TCP connect to Anthropic can't hang the calling thread forever"
    (is (= (java.time.Duration/ofSeconds 10)
           (.orElse (.connectTimeout @@#'llm-executor/anthropic-http-client) nil)))))
