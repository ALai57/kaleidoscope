(ns kaleidoscope.scoring.llm-scorer-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.scoring.llm-scorer :as llm-scorer]))

(deftest http-client-has-a-connect-timeout-test
  (testing "A stalled TCP connect to Anthropic can't hang the calling thread forever"
    (is (= (java.time.Duration/ofSeconds 10)
           (.orElse (.connectTimeout (#'llm-scorer/make-http-client)) nil)))))
