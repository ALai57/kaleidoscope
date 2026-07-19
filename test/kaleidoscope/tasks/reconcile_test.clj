(ns kaleidoscope.tasks.reconcile-test
  "Tests the PURE reconciliation core — the single most dangerous decision in the
  media plan (which bytes move toward deletion). No S3, no DB: just set-math and
  gates over in-memory sets."
  (:require [clojure.set :as set]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.tasks.reconcile :as sut]))

(deftest orphans-and-dangling-are-the-set-differences
  (let [plan (sut/reconcile-plan {:stored     #{"a" "b" "c"}
                                  :referenced #{"b" "c" "d"}})]
    (testing "orphans = stored - referenced"
      (is (= #{"a"} (:orphans plan))))
    (testing "dangling = referenced - stored"
      (is (= #{"d"} (:dangling plan))))
    (testing "orphans and dangling are disjoint, and partition the symmetric difference"
      (is (empty? (set/intersection (:orphans plan) (:dangling plan))))
      (is (= (set/union #{"a"} #{"d"})
             (set/union (:orphans plan) (:dangling plan)))))
    (testing "gate is :ok on a healthy, stable index"
      (is (= :ok (:gate plan))))))

(deftest content-hash-mismatch-is-flagged-only-when-both-hashes-are-present
  (let [plan (sut/reconcile-plan {:stored        #{"good" "bad" "unhashed"}
                                  :referenced    #{"good" "bad" "unhashed"}
                                  ;; DB-recorded checksums (pre-Phase-3 rows carry none)
                                  :hashes        {"good" "sha256:aaa" "bad" "sha256:bbb"}
                                  ;; checksums the driver recomputed from the stored bytes
                                  :object-hashes {"good" "sha256:aaa" "bad" "sha256:ZZZ"}})]
    (testing "a key whose stored checksum disagrees with content_hash is mismatched"
      (is (contains? (:mismatched plan) "bad")))
    (testing "a key whose checksums agree is not mismatched"
      (is (not (contains? (:mismatched plan) "good"))))
    (testing "a key with no stored content_hash (pre-Phase-3 corpus) is skipped, never flagged"
      (is (not (contains? (:mismatched plan) "unhashed"))))))

(deftest shrink-gate-refuses-to-quarantine-on-a-suspicious-index
  (let [plan (sut/reconcile-plan {:stored                #{"s0" "s1" "s2" "s3" "s4" "s5" "s6" "s7" "s8" "s9"}
                                  :referenced            #{"s0" "missing"}          ;; 2 of a former 10 = 80% drop
                                  :last-referenced-count 10})]
    (testing "gate aborts on the shrink"
      (is (= :abort-shrink (:gate plan))))
    (testing "the orphan action set is empty — nothing is quarantined on a suspicious index"
      (is (= #{} (:orphans plan))))
    (testing "dangling is still reported (an alert, not a destructive action)"
      (is (= #{"missing"} (:dangling plan))))))

(deftest a-small-referenced-drop-within-threshold-still-runs
  (let [plan (sut/reconcile-plan {:stored                #{"a" "b" "c" "orphan"}
                                  :referenced            #{"a" "b" "c"}     ;; 3 of 3 prior — no drop
                                  :last-referenced-count 3})]
    (is (= :ok (:gate plan)))
    (is (= #{"orphan"} (:orphans plan)))))

(deftest unhealthy-index-aborts-and-quarantines-nothing
  (let [plan (sut/reconcile-plan {:stored         #{"a" "orphan"}
                                  :referenced     #{"a"}
                                  :index-healthy? false})]
    (is (= :abort-unhealthy (:gate plan)))
    (is (= #{} (:orphans plan)))))

(deftest orphans-are-quarantined-not-hard-deleted
  (is (= "trash/media/abc/raw.jpg" (sut/quarantine-key "media/abc/raw.jpg"))))

(deftest derivable-keys-reduces-rows-to-referenced-set-and-hashes
  (let [{:keys [referenced hashes]}
        (sut/derivable-keys [{:path "media/a/raw.jpg"       :content-hash "sha256:aaa"}
                             {:path "media/a/thumbnail.jpg" :content-hash nil}      ;; rendition, no hash yet
                             {:path "media/b/raw.jpg"       :content-hash ""}])]    ;; blank treated as absent
    (testing "every row's path is referenced"
      (is (= #{"media/a/raw.jpg" "media/a/thumbnail.jpg" "media/b/raw.jpg"} referenced)))
    (testing "only rows with a non-blank content_hash contribute a hash entry"
      (is (= {"media/a/raw.jpg" "sha256:aaa"} hashes)))))
