(ns kaleidoscope.api.curation-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.curation :as curation]
            [kaleidoscope.api.interests :as interests-api]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.workflows.mock :as workflow-mock]
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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Curation orchestration (mock executor)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def user-id "reader@example.com")

(defn- make-interest!
  [db & {:as taste-overrides}]
  (interests-api/create-interest!
   db user-id
   {:intent        "Investigative journalism about technology and power"
    :taste-profile (merge {:trusted-sources ["PBS Frontline" "The Hill"]
                           :novelty-ratio   0.5
                           :formats         ["article" "podcast"]}
                          taste-overrides)}))

(deftest seed-curation-workflow-test
  (let [db (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")]
    (curation/seed-curation-workflow! db user-id)
    (let [wf (curation/get-curation-workflow db user-id)]
      (testing "the Interest Curation workflow is live with clarify → discover steps"
        (is (match? {:name   "Interest Curation"
                     :status "live"
                     :steps  [{:name "Refine Interest" :agent-type "librarian" :output-kind "clarify"}
                              {:name "Discover Resources" :agent-type "librarian" :output-kind "text"}]}
                    wf)))
      (testing "re-seeding is idempotent"
        (curation/seed-curation-workflow! db user-id)
        (is (= (:id wf) (:id (curation/get-curation-workflow db user-id))))))))

(deftest run-curation-shelves-with-novelty-split-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        result   (curation/run-curation! db executor user-id (:id interest) {})]
    (testing "the run completes and reports the finite shelf's composition"
      (is (match? {:status  "completed"
                   :summary {:total 6 :trusted 3 :novel 3}}
                  result)))
    (testing "shelved cards persist with a why rationale and origin tags"
      (let [shelf (interests-api/get-shelf db user-id (:id interest) {:status "shelved"})]
        (is (= 6 (count shelf)))
        (is (every? (comp seq :why) shelf))
        (is (= 3 (count (filter #(= "trusted" (:origin %)) shelf))))
        (is (= 3 (count (filter #(= "novel" (:origin %)) shelf))))
        (is (every? #(contains? #{"PBS Frontline" "The Hill"} (:source %))
                    (filter #(= "trusted" (:origin %)) shelf)))))
    (testing "below-threshold candidates are dropped (Noise Weekly scores 2.0 in the mock pool)"
      (is (empty? (filter #(= "Noise Weekly" (:source %))
                          (interests-api/get-shelf db user-id (:id interest) {})))))
    (testing "the backing project description carries the taste-profile context for LLM prompts"
      (is (str/includes?
           (:description (projects-persistence/get-project db (:project-id interest) user-id))
           "<taste_profile>")))))

(deftest run-curation-ownership-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (testing "a non-owner cannot curate someone else's interest"
      (is (nil? (curation/run-curation! db executor "attacker@example.com" (:id interest) {}))))))

(deftest re-curation-replaces-the-shelf-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (curation/run-curation! db executor user-id (:id interest) {})
    (curation/run-curation! db executor user-id (:id interest) {})
    (testing "the shelf stays finite: the previous run's items are archived, not accumulated"
      (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))
      (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "archived"})))))))

(deftest run-curation-respects-shelf-size-and-scrutiny-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        result   (curation/run-curation! db executor user-id (:id interest)
                                         {:shelf-size 4 :scrutiny "rigorous"})]
    (testing "shelf size is honored and rigorous threshold (7.0) drops the 6.x novel candidates"
      (is (match? {:summary {:total 4 :trusted 2 :novel 2}} result))
      (is (every? #(not (contains? #{"Asterisk Magazine" "The Browser" "Noise Weekly"} (:source %)))
                  (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clarify answers: fold into taste profile, then resume
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- paused-curation-run!
  "Create a curation run whose clarify step is awaiting input. The mock
  planner always reports ready, so pause the step manually to exercise the
  respond path deterministically."
  [db interest]
  (curation/seed-curation-workflow! db user-id)
  (let [wf   (curation/get-curation-workflow db user-id)
        run  (workflows-persistence/create-workflow-run!
              db (:project-id interest) (:id wf) "autonomous"
              (assoc (curation/relevance-config nil) :shelf-size 6))
        step (->> (:steps run) (filter #(= "Refine Interest" (:name %))) first)]
    (workflows-persistence/update-step-run!
     db (:id step) {:status "awaiting_input"
                    :output (json/encode {:reply "Which beats matter most to you?"})})
    (workflows-persistence/update-workflow-run! db (:id run) {:status "awaiting_input"})
    {:run run :step step}))

(deftest respond-to-curation-step-folds-answers-and-resumes-test
  (let [db         (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor   (workflow-mock/make-mock-executor)
        interest   (make-interest! db)
        {:keys [run step]} (paused-curation-run! db interest)
        result     (curation/respond-to-curation-step!
                    db executor user-id (:id interest) (:id run) (:id step)
                    ["Prefer primary reporting over commentary"])]
    (testing "the answer is folded into the taste profile's refinements"
      (is (match? {:taste-profile {:refinements ["Prefer primary reporting over commentary"]}}
                  (interests-api/get-interest db user-id (:id interest)))))
    (testing "the run resumes through discovery and shelves synchronously"
      (is (match? {:status "completed" :summary {:total 6}} result)))
    (testing "the clarify step is completed, not re-runnable"
      (is (match? {:status "completed"}
                  (workflows-persistence/get-step-run db (:id step)))))))

(deftest respond-to-curation-step-guards-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        {:keys [run step]} (paused-curation-run! db interest)]
    (testing "non-owners get nil"
      (is (nil? (curation/respond-to-curation-step!
                 db executor "attacker@example.com" (:id interest) (:id run) (:id step) ["x"]))))
    (testing "a run that doesn't belong to the interest gets nil"
      (let [other (make-interest! db)]
        (is (nil? (curation/respond-to-curation-step!
                   db executor user-id (:id other) (:id run) (:id step) ["x"])))))
    (testing "a step that isn't awaiting input gets nil"
      (workflows-persistence/update-step-run! db (:id step) {:status "completed"})
      (is (nil? (curation/respond-to-curation-step!
                 db executor user-id (:id interest) (:id run) (:id step) ["x"]))))))

(deftest taste-profile-retune-changes-composition-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (testing "novelty 1.0 — the next shelf is entirely novel"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio 1.0}})
      (is (match? {:summary {:total 6 :trusted 0 :novel 6}}
                  (curation/run-curation! db executor user-id (:id interest) {}))))
    (testing "novelty 0.0 — the next shelf is entirely trusted"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio 0.0}})
      (is (match? {:summary {:total 6 :trusted 6 :novel 0}}
                  (curation/run-curation! db executor user-id (:id interest) {}))))
    (testing "shrinking the allowlist changes what counts as trusted"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio   0.0
                                                       :trusted-sources ["The Hill"]}})
      (let [result (curation/run-curation! db executor user-id (:id interest) {})]
        ;; only 3 mock candidates come from the single trusted source; the
        ;; rest of the shelf backfills from novel candidates
        (is (match? {:summary {:total 6 :trusted 3 :novel 3}} result))))))
