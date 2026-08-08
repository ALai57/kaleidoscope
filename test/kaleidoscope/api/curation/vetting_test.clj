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

(deftest select-shelf-swap-sort-and-cap
  (let [mk  (fn [title composite]
              {:candidate {:title title}
               :verdict {:matches? true :composite composite :scores {}}})
        a   (mk "A" 9.0)
        b   (mk "B" 8.5)
        c   (mk "C" 8.0)
        d   (mk "D" 7.5)
        cfg {:shelf-size 3 :min-viable 3}]
    (testing "4 distinct-composite shelvable candidates, shelf-size 3 < survivor count -> swapped, capped at shelf-size, sorted by composite desc"
      (let [{:keys [shelved disposition]} (vet/select-shelf [d c a b] rubric cfg)]
        (is (= :swapped disposition) "3 survivors after cap >= min-viable 3 -> swapped")
        (is (= 3 (count shelved)) "take shelf-size caps the shelf at 3")
        (is (= ["A" "B" "C"] (map :title shelved)) "sorted by composite descending, lowest (D) dropped by the cap")
        (is (= [9.0 8.5 8.0] (map :composite shelved)))))))
