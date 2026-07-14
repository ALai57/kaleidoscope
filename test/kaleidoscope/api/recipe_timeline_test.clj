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
                                   ["sear" "active" 10 ["marinate"]]]) [])]
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
    (let [packed (tl/pack (comp1 [["a" "active" 10 ["ghost"]]]) [])]
      (is (match? {"a" 0} (starts packed))))))

(deftest pack-breaks-cycles-test
  (testing "a dependency cycle does not hang; every phase gets a start"
    (let [packed (tl/pack (comp1 [["a" "active" 3 ["b"]]
                                   ["b" "active" 3 ["a"]]]) [])]
      (is (every? some? (vals (starts packed)))))))

(def content-a
  {:title "R"
   :sections [{:name "Salmon" :ingredients [] :steps ["marinate" "sear"]}
              {:name "Rice"   :ingredients [] :steps ["rinse" "simmer"]}]})

(deftest content-fingerprint-test
  (testing "one entry per section, id from name, stable hash"
    (let [fp (tl/content-fingerprint content-a)]
      (is (= ["Salmon" "Rice"] (mapv :id fp)))
      (is (= (tl/steps-hash ["marinate" "sear"]) (:steps-hash (first fp)))))))

(deftest component-id-falls-back-to-ordinal-test
  (is (= "Section 1" (tl/component-id {:name nil :steps []} 0)))
  (is (= "Section 2" (tl/component-id {:name "  " :steps []} 1)))
  (is (= "Salmon" (tl/component-id {:name "Salmon" :steps []} 0))))

(deftest changed-ids-test
  (let [stored {:components [{:name "Salmon" :steps-hash (tl/steps-hash ["marinate" "sear"]) :phases []}
                            {:name "Rice"   :steps-hash (tl/steps-hash ["rinse" "simmer"]) :phases []}]}
        edited (assoc-in content-a [:sections 0 :steps] ["marinate" "sear" "rest"])]
    (testing "nothing changed" (is (= #{} (tl/changed-ids content-a stored))))
    (testing "one component's steps changed" (is (= #{"Salmon"} (tl/changed-ids edited stored))))
    (testing "no stored timeline ⇒ everything is changed"
      (is (= #{"Salmon" "Rice"} (tl/changed-ids content-a nil))))))

(deftest resolve-deps-drops-unknown-test
  (let [comps [{:name "C" :steps-hash "h"
                :phases [{:id "C/a" :label "a" :kind "active" :steps [] :estimate 1 :deps ["C/ghost" "C/b"]}
                         {:id "C/b" :label "b" :kind "active" :steps [] :estimate 1 :deps []}]}]]
    (is (= ["C/b"] (-> (tl/resolve-deps comps) first :phases first :deps)))))

(deftest surviving-overrides-test
  (let [stored {:overrides [{:phase "Salmon/Sear" :minutes 12}
                            {:phase "Rice/Simmer" :minutes 20}]}]
    (is (= [{:phase "Rice/Simmer" :minutes 20}]
           (tl/surviving-overrides stored #{"Salmon"})))))

(deftest with-overrides-repacks-test
  (let [tline {:version 1 :generator-version 1 :generated-at "t" :total-minutes 15
               :overrides []
               :components [{:name "C" :steps-hash "h"
                             :phases [{:id "C/a" :label "a" :kind "active" :steps [] :estimate 10 :deps []}
                                      {:id "C/b" :label "b" :kind "active" :steps [] :estimate 5 :deps []}]}]}
        out   (tl/with-overrides tline [{:phase "C/a" :minutes 4}])]
    (is (= [{:phase "C/a" :minutes 4}] (:overrides out)))
    (is (= 9 (:total-minutes out)))))

(deftest assemble-trust-boundary-test
  (let [content  {:title "R"
                  :sections [{:name "Salmon" :ingredients [] :steps ["marinate" "sear"]}
                             {:name "Rice"   :ingredients [] :steps ["rinse" "simmer"]}]}
        stored   {:components
                  [{:name "Salmon" :steps-hash "STALE"
                    :phases [{:id "Salmon/cached" :label "cached" :kind "active" :steps [] :estimate 1 :deps []}]}
                   {:name "Rice" :steps-hash "STALE"
                    :phases [{:id "Rice/cached" :label "cached" :kind "active" :steps [] :estimate 1 :deps []}]}]}
        proposal {:components
                  [{:name "Salmon"
                    :phases [{:id "Salmon/fresh" :label "fresh" :kind "active" :steps [] :estimate 2 :deps []}]}
                   {:name "Rice"
                    :phases [{:id "Rice/fresh" :label "fresh" :kind "active" :steps [] :estimate 2 :deps []}]}]}
        out      (tl/assemble content proposal stored #{"Salmon"})
        by-name  (into {} (map (juxt :name identity)) out)]
    (testing "changed component takes the proposal's phases"
      (is (= ["Salmon/fresh"] (mapv :id (:phases (by-name "Salmon"))))))
    (testing "unchanged component keeps the CACHED phases (proposal ignored)"
      (is (= ["Rice/cached"] (mapv :id (:phases (by-name "Rice"))))))
    (testing "steps-hash is refreshed from current content, not the stale stored hash"
      (is (= (tl/steps-hash ["marinate" "sear"]) (:steps-hash (by-name "Salmon"))))
      (is (not= "STALE" (:steps-hash (by-name "Rice")))))))

(require '[kaleidoscope.timeline.mock :as mock])

(deftest generate!-first-time-test
  (let [tline (tl/generate! {:generator (mock/make-mock-generator)
                             :content content-a :stored nil
                             :generator-version 1 :now "t0"})]
    (testing "blob shape + packed"
      (is (match? {:version 1 :generator-version 1 :generated-at "t0" :overrides []}
                  tline))
      (is (= ["Salmon" "Rice"] (mapv :name (:components tline))))
      (is (every? (fn [c] (every? #(some? (:start %)) (:phases c))) (:components tline))))))

(deftest generate!-short-circuits-when-unchanged-test
  (let [stored (tl/generate! {:generator (mock/make-mock-generator)
                              :content content-a :stored nil
                              :generator-version 1 :now "t0"})]
    (testing "no changes + current version ⇒ returns stored untouched (no regen)"
      (is (identical? stored (tl/generate! {:generator (reify kaleidoscope.timeline.protocol/ITimelineGenerator
                                                         (segment [_ _ _ _] (throw (ex-info "should not run" {}))))
                                            :content content-a :stored stored
                                            :generator-version 1 :now "t1"}))))))

(deftest generate!-keeps-override-on-unchanged-component-test
  (let [gen    (mock/make-mock-generator)
        base   (tl/generate! {:generator gen :content content-a :stored nil
                              :generator-version 1 :now "t0"})
        nudged (tl/with-overrides base [{:phase "Rice/Rice" :minutes 40}])
        edited (assoc-in content-a [:sections 0 :steps] ["marinate" "sear" "rest"])
        regen  (tl/generate! {:generator gen :content edited :stored nudged
                              :generator-version 1 :now "t2"})]
    (testing "editing Salmon regenerates only Salmon; Rice override survives"
      (is (= [{:phase "Rice/Rice" :minutes 40}] (:overrides regen)))
      ;; Salmon re-estimated: 3 steps ⇒ 2 + 3*3 = 11
      (is (= 11 (->> regen :components (filter #(= "Salmon" (:name %))) first :phases first :estimate))))))
