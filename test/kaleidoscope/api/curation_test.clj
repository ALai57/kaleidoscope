(ns kaleidoscope.api.curation-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.curation :as curation]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest novelty-quota-test
  (testing "0.0 — pure exploit: the whole shelf is trusted"
    (is (= {:novel 0 :trusted 6} (curation/novelty-quota 6 0.0))))
  (testing "1.0 — pure explore: the whole shelf is novel"
    (is (= {:novel 6 :trusted 0} (curation/novelty-quota 6 1.0))))
  (testing "0.5 on an even shelf splits exactly"
    (is (= {:novel 3 :trusted 3} (curation/novelty-quota 6 0.5))))
  (testing "0.5 on an odd shelf rounds the extra slot to novel (explore wins ties)"
    (is (= {:novel 3 :trusted 2} (curation/novelty-quota 5 0.5))))
  (testing "intermediate ratios round to the nearest slot"
    (is (= {:novel 2 :trusted 4} (curation/novelty-quota 6 0.25)))
    (is (= {:novel 4 :trusted 2} (curation/novelty-quota 6 0.7))))
  (testing "nil and out-of-range ratios are clamped, never thrown"
    (is (= {:novel 3 :trusted 3} (curation/novelty-quota 6 nil)))
    (is (= {:novel 0 :trusted 6} (curation/novelty-quota 6 -0.4)))
    (is (= {:novel 6 :trusted 0} (curation/novelty-quota 6 1.7)))))

(deftest tag-origin-test
  (let [candidates [{:title "a" :source "PBS Frontline"}
                    {:title "b" :source "pbs frontline"}
                    {:title "c" :source "The Gradient"}
                    {:title "d" :source nil}]]
    (testing "membership in trusted-sources is case-insensitive; everything else is novel"
      (is (match? [{:origin "trusted"} {:origin "trusted"} {:origin "novel"} {:origin "novel"}]
                  (curation/tag-origin candidates ["PBS Frontline"]))))
    (testing "no trusted sources means everything is novel"
      (is (every? #(= "novel" (:origin %)) (curation/tag-origin candidates []))))))

(deftest drop-below-threshold-test
  (let [candidates [{:title "keep" :relevance 8.0}
                    {:title "edge" :relevance 6.0}
                    {:title "drop" :relevance 5.9}
                    {:title "no-score"}]]
    (testing "candidates at or above threshold survive; missing relevance counts as 0.0"
      (is (= ["keep" "edge"] (mapv :title (curation/drop-below-threshold candidates 6.0)))))))

(deftest split-candidates-test
  (let [pool [{:title "t1" :origin "trusted" :relevance 9.0}
              {:title "t2" :origin "trusted" :relevance 8.0}
              {:title "t3" :origin "trusted" :relevance 7.0}
              {:title "n1" :origin "novel" :relevance 8.5}
              {:title "n2" :origin "novel" :relevance 7.5}
              {:title "n3" :origin "novel" :relevance 6.5}]]
    (testing "fills each quota with the best-relevance candidates of that origin"
      (is (= #{"t1" "t2" "n1"}
             (set (map :title (curation/split-candidates pool {:trusted 2 :novel 1}))))))
    (testing "quota of zero on one side selects none of that origin"
      (is (every? #(= "novel" (:origin %))
                  (curation/split-candidates pool {:trusted 0 :novel 3}))))
    (testing "a thin pool backfills from the other origin instead of under-filling"
      (is (= 5 (count (curation/split-candidates pool {:trusted 2 :novel 3}))))
      (is (= 6 (count (curation/split-candidates pool {:trusted 6 :novel 0}))))
      (is (= 3 (count (curation/split-candidates
                       (filter #(= "trusted" (:origin %)) pool)
                       {:trusted 0 :novel 6})))))
    (testing "an empty pool selects nothing"
      (is (= [] (curation/split-candidates [] {:trusted 3 :novel 3}))))))

(deftest parse-candidates-test
  (testing "parses the librarian JSON contract and normalizes est_time"
    (is (match? [{:title "A" :est-time "18 min" :relevance 8.0}]
                (curation/parse-candidates
                 (json/encode {:candidates [{:title "A" :est_time "18 min" :relevance 8.0}]})))))
  (testing "malformed output shelves nothing rather than throwing"
    (is (= [] (curation/parse-candidates "not json at all")))
    (is (= [] (curation/parse-candidates nil)))))

(deftest relevance-config-test
  (testing "thresholds mirror the scrutiny ladder and default to standard"
    (is (= 5.0 (:relevance-threshold (curation/relevance-config "quick"))))
    (is (= 6.0 (:relevance-threshold (curation/relevance-config "standard"))))
    (is (= 7.0 (:relevance-threshold (curation/relevance-config "rigorous"))))
    (is (= 6.0 (:relevance-threshold (curation/relevance-config nil))))))
