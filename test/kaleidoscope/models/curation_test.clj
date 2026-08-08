(ns kaleidoscope.models.curation-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.models.curation :as m]
            [malli.core :as malli]))

(deftest verdict-schema-test
  (testing "verdict carries identity gate + open dimension scores + composite"
    (is (malli/validate m/Verdict
          {:matches? true
           :scores {:relevance 8 :depth 7 :taste-fit 9 :novelty 4}
           :composite 7.4 :why {} :est-time "18 min" :reason "on-taste"}))
    (is (not (malli/validate m/Verdict {:matches? true})) "scores + composite required")))

(deftest rubric-schema-and-presets-test
  (testing "a rubric is dimensions + pass-line + aggregate; presets validate"
    (is (malli/validate m/Rubric (get m/default-rubric "standard")))
    (is (= :weighted-mean (:aggregate (get m/default-rubric "standard"))))))
