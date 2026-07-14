(ns kaleidoscope.api.recipe-timeline-test
  (:require [kaleidoscope.api.recipe-timeline :as tl]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(defn- comp1
  "One component with the given phases (each: [id kind estimate deps])."
  [phases]
  [{:name "C" :steps-hash "h"
    :phases (mapv (fn [[id kind est deps]]
                    {:id id :label id :kind kind :steps [] :estimate est :deps deps})
                  phases)}])

(defn- starts [{:keys [components]}]
  (into {} (for [c components p (:phases c)] [(:id p) (:start p)])))

(deftest pack-serializes-active-phases-test
  (testing "two independent active phases never overlap (single cook)"
    (let [packed (tl/pack (comp1 [["a" "active" 10 []] ["b" "active" 5 []]]) [])]
      (is (match? {"a" 0 "b" 10} (starts packed)))
      (is (= 15 (:total-minutes packed))))))

(deftest pack-floats-passive-phases-test
  (testing "a passive phase starts at 0 and overlaps active work"
    (let [packed (tl/pack (comp1 [["marinate" "passive" 24 []]
                                   ["a" "active" 10 []]
                                   ["b" "active" 5 []]]) [])]
      (is (match? {"marinate" 0 "a" 0 "b" 10} (starts packed)))
      (is (= 24 (:total-minutes packed))))))

(deftest pack-respects-dependencies-test
  (testing "a phase can't start before its dep finishes"
    (let [packed (tl/pack (comp1 [["marinate" "passive" 24 []]
                                   ["sear" "active" 10 ["C/marinate"]]]) [])]
      (is (match? {"marinate" 0 "sear" 24} (starts packed)))
      (is (= 34 (:total-minutes packed))))))

(deftest pack-applies-override-test
  (testing "override replaces the estimate for that phase"
    (let [packed (tl/pack (comp1 [["a" "active" 10 []] ["b" "active" 5 []]])
                          [{:phase "a" :minutes 4}])]
      (is (match? {"a" 0 "b" 4} (starts packed)))
      (is (= 9 (:total-minutes packed))))))

(deftest pack-tolerates-dangling-dep-test
  (testing "a dep on a nonexistent phase is ignored, not fatal"
    (let [packed (tl/pack (comp1 [["a" "active" 10 ["C/ghost"]]]) [])]
      (is (match? {"a" 0} (starts packed))))))

(deftest pack-breaks-cycles-test
  (testing "a dependency cycle does not hang; every phase gets a start"
    (let [packed (tl/pack (comp1 [["a" "active" 3 ["C/b"]]
                                   ["b" "active" 3 ["C/a"]]]) [])]
      (is (every? some? (vals (starts packed)))))))
