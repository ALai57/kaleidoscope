(ns kaleidoscope.timeline.mock-test
  (:require [kaleidoscope.timeline.mock :as mock]
            [kaleidoscope.timeline.protocol :as protocol]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(def recipe
  {:content {:title "R"
             :sections [{:name "Salmon" :ingredients [] :steps ["marinate fish" "sear"]}
                        {:name "Plate"  :ingredients [] :steps ["assemble"]}]}})

(deftest mock-segments-each-component-test
  (let [{:keys [components]} (protocol/segment (mock/make-mock-generator) recipe #{"Salmon" "Plate"} nil)]
    (testing "one phase per component"
      (is (= ["Salmon" "Plate"] (mapv :name components)))
      (is (every? #(= 1 (count (:phases %))) components)))
    (testing "passive cue classifies, estimate scales with step count, last depends on earlier"
      (is (match? {:id "Salmon/Salmon" :kind "passive" :estimate 8 :deps []}
                  (-> components first :phases first)))
      (is (match? {:id "Plate/Plate" :kind "active" :estimate 5 :deps ["Salmon/Salmon"]}
                  (-> components second :phases first))))))
