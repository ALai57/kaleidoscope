(ns kaleidoscope.api.curation.vetting-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.vetting :as vet]
            [kaleidoscope.models.curation :as m]))

(def rubric (get m/default-rubric "standard"))

(deftest aggregate-is-weighted-mean
  (testing "composite = Σ(w·s)/Σw over rubric dimensions"
    ;; weights 1,1,1,0.5 ; scores 8,7,9,4 -> (8+7+9+2)/3.5 = 26/3.5 = 7.428...
    (is (< 7.42 (vet/aggregate {:relevance 8 :depth 7 :taste-fit 9 :novelty 4} rubric) 7.43))
    (testing "a missing dimension score counts as 0"
      (is (= 0.0 (vet/aggregate {} rubric))))))

(deftest select-shelf-drops-and-disposition
  (let [ok    {:candidate {:title "A" :origin "trusted"}
               :verdict {:matches? true :scores {:relevance 8 :depth 8 :taste-fit 8 :novelty 6} :composite 7.7}}
        wrong {:candidate {:title "B"} :verdict {:matches? false :composite 9.0 :scores {}}}
        thin  {:candidate {:title "C"} :verdict {:matches? true :composite 4.0 :scores {}}}
        dead  {:candidate {:title "D"} :fetched {:status :dead} :verdict nil}
        cfg   {:shelf-size 6 :min-viable 3}]
    (testing "identity fail -> off-taste, below pass-line -> below-threshold, dead -> dead"
      (let [{:keys [shelved dropped disposition]} (vet/select-shelf [ok wrong thin dead] rubric cfg)]
        (is (= #{"A"} (set (map :title shelved))))
        (is (= "off-taste"       (:outcome (first (filter #(= "B" (:title %)) dropped)))))
        (is (= "below-threshold" (:outcome (first (filter #(= "C" (:title %)) dropped)))))
        (is (= "dead"            (:outcome (first (filter #(= "D" (:title %)) dropped)))))
        (is (= :kept-prior disposition) "1 survivor < min-viable 3 -> kept-prior")))))
