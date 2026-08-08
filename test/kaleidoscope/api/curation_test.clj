(ns kaleidoscope.api.curation-test
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.curation :as curation]
            [kaleidoscope.api.curation.mock :as curation-mock]
            [kaleidoscope.api.curation.vet :as vet]
            [kaleidoscope.api.interests :as interests-api]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.workflows.mock :as workflow-mock]
            [kaleidoscope.workflows.protocol :as workflow-protocol]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

;; shelve! now runs every discovered candidate through the real fetch->parse
;; ->vet gate (kaleidoscope.api.curation.vetting/vet-candidates). vet/vet's
;; default path calls the Anthropic API, which these tests must never do —
;; redef it to the deterministic mock/vet used by curation.mock throughout
;; this namespace. Fetch is kept offline per-test via :fetch-policy [:mock]
;; in the run config (see run-curation-mocked! below).
(use-fixtures :each
  (fn [f]
    (with-redefs [vet/vet (fn [content _taste rubric _config]
                            (curation-mock/vet content _taste rubric))]
      (f))))

(defn- run-curation-mocked!
  "Test-only mirror of curation/run-curation! that injects :fetch-policy
  [:mock] into the run config so the vetting pipeline's fetch stage never
  reaches the network. The public run-curation!/CurationRequest surface only
  exposes :scrutiny + :shelf-size by design (see CurationRequest in
  http_api/interests.clj) — there's no way to reach :fetch-policy through the
  real API, so this reimplements run-curation!'s setup and drops straight
  into the (private) advance-and-shelve! with the extra config in place."
  [db executor user-id interest-id {:keys [scrutiny shelf-size]}]
  (when-let [interest (interests-persistence/get-interest db interest-id user-id)]
    (curation/seed-curation-workflow! db user-id)
    (#'curation/sync-backing-project! db interest)
    (let [wf     (curation/get-curation-workflow db user-id)
          config (assoc (curation/relevance-config scrutiny)
                        :shelf-size (or shelf-size curation/default-shelf-size)
                        :fetch-policy [:mock])
          run    (workflows-persistence/create-workflow-run!
                  db (:project-id interest) (:id wf) "autonomous" config)]
      (#'curation/advance-and-shelve! db executor user-id interest-id (:id run)))))

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
  (testing "strips a ```json markdown fence — the Librarian's actual output shape"
    (is (match? [{:title "A" :est-time "18 min" :relevance 10.0}]
                (curation/parse-candidates
                 (str "```json\n"
                      (json/encode {:candidates [{:title "A" :est_time "18 min" :relevance 10.0}]})
                      "\n```")))))
  (testing "strips a bare ``` fence and a prose preamble before the JSON"
    (is (match? [{:title "A"}]
                (curation/parse-candidates
                 (str "Here are the candidates:\n```\n"
                      (json/encode {:candidates [{:title "A" :relevance 8.0}]})
                      "\n```")))))
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
        result   (run-curation-mocked! db executor user-id (:id interest) {})]
    (testing "the run completes and reports the finite shelf's composition"
      (is (match? {:status      "completed"
                   :disposition :swapped
                   :summary     {:total 6 :trusted 3 :novel 3}}
                  result)))
    (testing "shelved cards persist with a why rationale and origin tags"
      (let [shelf (interests-api/get-shelf db user-id (:id interest) {:status "shelved"})]
        (is (= 6 (count shelf)))
        (is (every? (comp seq :why) shelf))
        (is (= 3 (count (filter #(= "trusted" (:origin %)) shelf))))
        (is (= 3 (count (filter #(= "novel" (:origin %)) shelf))))
        (is (every? #(contains? #{"PBS Frontline" "The Hill"} (:source %))
                    (filter #(= "trusted" (:origin %)) shelf)))))
    (testing "the lowest-ranked novel candidate (Noise Weekly) loses out to the shelf-size cap"
      ;; mock/vet scores every non-WRONG/NOISE-titled candidate identically, so
      ;; nothing is dropped by the rubric pass-line here — Noise Weekly simply
      ;; ranks last among the 7 novel-tagged survivors and the novelty quota
      ;; only has room for 3.
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
    (run-curation-mocked! db executor user-id (:id interest) {})
    (run-curation-mocked! db executor user-id (:id interest) {})
    (testing "the shelf stays finite: the previous run's items are archived, not accumulated"
      (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))
      (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "archived"})))))))

(defn- barren-executor
  "An executor that completes every step but yields no discovery candidates —
  the shape of a parse failure or an all-below-threshold discovery in prod."
  []
  (reify workflow-protocol/IWorkflowExecutor
    (execute-step! [_this db _project step-run _output-stream]
      (let [output (if (= :clarify (keyword (or (:output-kind step-run) "text")))
                     (json/encode {})
                     (json/encode {:candidates []}))]
        (workflows-persistence/update-step-run!
         db (:id step-run) {:status "completed" :output output})))
    (recommend-workflows [_this _project _live] nil)))

(deftest empty-discovery-preserves-existing-shelf-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        interest (make-interest! db)]
    (run-curation-mocked! db (workflow-mock/make-mock-executor) user-id (:id interest) {})
    (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))
    ;; barren-executor discovers zero candidates, so no fetch ever happens —
    ;; safe to run through the public run-curation! (default fetch-policy)
    ;; unchanged.
    (let [result (curation/run-curation! db (barren-executor) user-id (:id interest) {})]
      (testing "the run completes but shelves nothing new"
        (is (match? {:status "completed" :disposition :kept-prior :summary {:total 0}} result)))
      (testing "an empty discovery leaves the existing shelf intact rather than blanking it"
        (is (= 6 (count (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))
        (is (= 0 (count (interests-api/get-shelf db user-id (:id interest) {:status "archived"}))))))))

(deftest run-curation-respects-shelf-size-and-scrutiny-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)]
    (testing "shelf size is honored under standard scrutiny"
      (let [result (run-curation-mocked! db executor user-id (:id interest) {:shelf-size 4})]
        (is (match? {:disposition :swapped :summary {:total 4 :trusted 2 :novel 2}} result))))
    (testing "rigorous scrutiny raises the rubric pass-line (8.0) above what the deterministic mock
              vet ever scores (~7.71), so nothing clears the bar and the prior shelf is kept"
      (let [result (run-curation-mocked! db executor user-id (:id interest)
                                         {:shelf-size 4 :scrutiny "rigorous"})]
        (is (match? {:status "completed" :disposition :kept-prior :summary {:total 0}} result))
        (is (= 4 (count (interests-api/get-shelf db user-id (:id interest) {:status "shelved"}))))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clarify answers: fold into taste profile, then resume
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- paused-curation-run!
  "Create a curation run whose clarify step is awaiting input. The mock
  planner always reports ready, so pause the step manually to exercise the
  respond path deterministically. :fetch-policy [:mock] is baked into the
  config here because respond-to-curation-step-folds-answers-and-resumes-test
  resumes this run all the way through discover -> vet -> shelve."
  [db interest]
  (curation/seed-curation-workflow! db user-id)
  (let [wf   (curation/get-curation-workflow db user-id)
        run  (workflows-persistence/create-workflow-run!
              db (:project-id interest) (:id wf) "autonomous"
              (assoc (curation/relevance-config nil) :shelf-size 6 :fetch-policy [:mock]))
        step (->> (:steps run) (filter #(= "Refine Interest" (:name %))) first)]
    (workflows-persistence/update-step-run!
     db (:id step) {:status "awaiting_input"
                    :output (json/encode {:reply "Which beats matter most to you?"})})
    (workflows-persistence/update-workflow-run! db (:id run) {:status "awaiting_input"})
    {:run run :step step}))

(deftest run-curation-awaiting-input-carries-step-run-id-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        interest (make-interest! db)
        ;; A Librarian that decides the interest is too thin and pauses the
        ;; clarify step for user input, the way the real refine step does.
        executor (reify workflow-protocol/IWorkflowExecutor
                   (execute-step! [_this db _project step-run _output-stream]
                     (workflows-persistence/update-step-run!
                      db (:id step-run)
                      {:status "awaiting_input"
                       :output (json/encode {:reply "Which beats matter most to you?"})}))
                   (recommend-workflows [_this _project _live] nil))
        result   (curation/run-curation! db executor user-id (:id interest)
                                         {:scrutiny "standard"})]
    (testing "the pause result carries the step-run-id the client must POST answers back to"
      (is (match? {:status "awaiting_input" :run-id uuid? :step-run-id uuid?}
                  result)))
    (testing "the step-run-id names the step that is actually awaiting input"
      (let [paused (->> (:steps (workflows-persistence/get-workflow-run db (:run-id result)))
                        (filter #(= "awaiting_input" (:status %)))
                        first)]
        (is (= (:id paused) (:step-run-id result)))))))

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
                  (run-curation-mocked! db executor user-id (:id interest) {}))))
    (testing "novelty 0.0 — the next shelf is entirely trusted"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio 0.0}})
      (is (match? {:summary {:total 6 :trusted 6 :novel 0}}
                  (run-curation-mocked! db executor user-id (:id interest) {}))))
    (testing "shrinking the allowlist changes what counts as trusted"
      (interests-api/update-interest! db user-id (:id interest)
                                      {:taste-profile {:novelty-ratio   0.0
                                                       :trusted-sources ["The Hill"]}})
      (let [result (run-curation-mocked! db executor user-id (:id interest) {})]
        ;; only 3 mock candidates come from the single trusted source; the
        ;; rest of the shelf backfills from novel candidates
        (is (match? {:summary {:total 6 :trusted 3 :novel 3}} result))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Grounded vetting tail: fetch -> parse -> vet -> select-shelf
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- dead-links-executor
  "An executor whose Discover step reports real-looking candidates that all
  404 at fetch time — the shape of a Librarian hallucinating URLs. Every
  candidate's url lives under /dead/, which curation.mock/fetch (the
  :fetch-policy [:mock] fetcher) always reports :dead."
  []
  (reify workflow-protocol/IWorkflowExecutor
    (execute-step! [_this db _project step-run _output-stream]
      (let [output (if (= :clarify (keyword (or (:output-kind step-run) "text")))
                     (json/encode {})
                     (json/encode
                      {:candidates
                       [{:kind "article" :title "Vanished primer" :source "PBS Frontline"
                         :url "https://example.com/dead/1" :est_time "10 min"
                         :why "looks right" :relevance 9.0}
                        {:kind "article" :title "Vanished roundup" :source "The Gradient"
                         :url "https://example.com/dead/2" :est_time "10 min"
                         :why "looks right" :relevance 8.0}]}))]
        (workflows-persistence/update-step-run!
         db (:id step-run) {:status "completed" :output output})))
    (recommend-workflows [_this _project _live] nil)))

(deftest healthy-discovery-swaps-with-vetting-metadata-and-final-url-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        executor (workflow-mock/make-mock-executor)
        interest (make-interest! db)
        ;; Redefine the :mock fetcher (not just vet) for this one test so the
        ;; fetch stage's :final-url visibly differs from the discovered url —
        ;; proving the shelf persists the post-fetch url, not the raw
        ;; discovery url, rather than trivially matching because the stock
        ;; mock fetcher happens to echo the input url back unchanged.
        orig-fetch curation-mock/fetch
        result   (with-redefs [curation-mock/fetch
                                (fn [{:keys [url] :as ref} config]
                                  (let [base (orig-fetch ref config)]
                                    (cond-> base
                                      (= :ok (:status base)) (assoc :final-url (str url "?resolved")))))]
                   (run-curation-mocked! db executor user-id (:id interest) {}))]
    (testing "the run swaps because the vetted survivors clear min-viable"
      (is (match? {:status "completed" :disposition :swapped :summary {:total 6}} result)))
    (testing "every shelved recommendation carries vetting composite + scores"
      (let [shelf (interests-api/get-shelf db user-id (:id interest) {:status "shelved"})]
        (is (= 6 (count shelf)))
        (is (every? #(number? (get-in % [:vetting :composite])) shelf))
        (is (every? #(map? (get-in % [:vetting :scores])) shelf))
        (testing "the shelved url is the fetch stage's final-url, not the raw discovered url"
          (is (every? #(str/ends-with? (:url %) "?resolved") shelf)))))))

(deftest all-dead-discovery-keeps-prior-shelf-intact-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        interest (make-interest! db)
        seeded   (run-curation-mocked! db (workflow-mock/make-mock-executor) user-id (:id interest) {})
        prior    (interests-api/get-shelf db user-id (:id interest) {:status "shelved"})]
    (is (match? {:disposition :swapped} seeded))
    (is (= 6 (count prior)))
    (let [result (run-curation-mocked! db (dead-links-executor) user-id (:id interest) {})]
      (testing "every candidate 404s at fetch, so nothing clears min-viable"
        (is (match? {:status "completed" :disposition :kept-prior :summary {:total 0}} result)))
      (testing "the prior shelf is untouched — archive-shelved! was never called"
        (is (= (set (map :id prior))
               (set (map :id (interests-api/get-shelf db user-id (:id interest) {:status "shelved"})))))
        (is (= 0 (count (interests-api/get-shelf db user-id (:id interest) {:status "archived"}))))))))
