(ns kaleidoscope.timeline.llm-generator-test
  (:require [kaleidoscope.timeline.llm-generator :as llm]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match? thrown-match?]]))

(deftest parse-segment-response-test
  (testing "strips fences and returns components"
    (let [text "```json\n{\"components\":[{\"name\":\"Salmon\",\"phases\":[{\"id\":\"Salmon/Marinate\",\"label\":\"Marinate\",\"kind\":\"passive\",\"steps\":[0],\"estimate\":24,\"deps\":[]}]}]}\n```"]
      (is (match? {:components [{:name "Salmon"
                                 :phases [{:id "Salmon/Marinate" :label "Marinate"
                                           :kind "passive" :estimate 24 :deps []}]}]}
                  (llm/parse-segment-response text)))))
  (testing "malformed JSON throws a generation error"
    (is (thrown-match? clojure.lang.ExceptionInfo {:type :generation}
                       (llm/parse-segment-response "not json at all"))))
  (testing "valid JSON whose :components is missing or not sequential throws a generation error"
    (is (thrown-match? clojure.lang.ExceptionInfo {:type :generation}
                       (llm/parse-segment-response "{\"components\":\"nope\"}")))
    (is (thrown-match? clojure.lang.ExceptionInfo {:type :generation}
                       (llm/parse-segment-response "{\"foo\":1}"))))
  (testing "nil text throws a generation error, not an NPE"
    (is (thrown-match? clojure.lang.ExceptionInfo {:type :generation}
                       (llm/parse-segment-response nil)))))
