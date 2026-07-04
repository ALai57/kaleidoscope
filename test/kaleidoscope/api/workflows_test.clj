(ns kaleidoscope.api.workflows-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.workflows :as workflows]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.workflows.protocol :as wf-protocol]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def custom-workflow
  {:name        "Custom Workflow"
   :description "A user-authored workflow"
   :is-default  false
   :steps       [{:name        "Step 1"
                  :description "First step"
                  :position    0
                  :agent-type  "coach"
                  :output-kind "text"}]})

(deftest seed-default-workflows-reconciles-drifted-steps-test
  ;; Production incident, 2026-06-30 through 2026-07-04: once a default
  ;; workflow's stored steps drifted from its current code definition,
  ;; `seed-default-workflows!` called `persistence/update-workflow!` with the
  ;; wrong arity (missing user-id), so every subsequent call - including the
  ;; one triggered by every `POST /projects` via `start-default-workflow!` -
  ;; threw an ArityException. The blanket exception handler in
  ;; `http-api/projects.clj` then surfaced this as an opaque 400.
  (let [database (embedded-postgres/fresh-db!)
        user-id  "drifted-user@example.com"]
    (workflows/seed-default-workflows! database user-id)
    (let [live-default (->> (workflows-persistence/get-workflows database user-id)
                            (filter :is-default)
                            first)]
      ;; Force a drift: overwrite the stored steps so they no longer match
      ;; `feature-development-workflow`/`autonomous-team-review-workflow`,
      ;; simulating a default workflow definition changing in code without a
      ;; corresponding DB migration for already-seeded users.
      (workflows/update-workflow! database user-id (:id live-default) {:steps []})

      (testing "Re-seeding reconciles the drift instead of throwing"
        (workflows/seed-default-workflows! database user-id)
        (is (seq (:steps (workflows/get-workflow database user-id (:id live-default)))))))))

(deftest workflow-ownership-test
  (let [database   (embedded-postgres/fresh-db!)
        owner-id   "owner@example.com"
        other-id   "other@example.com"
        wf         (workflows/create-workflow! database owner-id custom-workflow)
        wf-id      (:id wf)]

    (testing "The owner can fetch their workflow"
      (is (match? {:id wf-id :name "Custom Workflow"}
                  (workflows/get-workflow database owner-id wf-id))))

    (testing "A different user cannot fetch someone else's workflow"
      (is (nil? (workflows/get-workflow database other-id wf-id))))

    (testing "A different user cannot update someone else's workflow"
      (is (nil? (workflows/update-workflow! database other-id wf-id {:name "Hijacked"})))
      (is (= "Custom Workflow" (:name (workflows/get-workflow database owner-id wf-id)))))

    (testing "The owner can update their own workflow"
      (is (match? {:name "Renamed"}
                  (workflows/update-workflow! database owner-id wf-id {:name "Renamed"}))))

    (testing "A different user cannot delete someone else's workflow"
      (is (match? {:error :not-found}
                  (workflows/delete-workflow! database other-id wf-id)))
      (is (some? (workflows/get-workflow database owner-id wf-id))))

    (testing "The owner can delete their own workflow"
      (is (not (:error (workflows/delete-workflow! database owner-id wf-id))))
      (is (nil? (workflows/get-workflow database owner-id wf-id))))))

(deftest workflow-run-project-scoping-test
  (let [database          (embedded-postgres/fresh-db!)
        owner-id          "owner@example.com"
        other-id          "other@example.com"
        wf                (workflows/create-workflow! database owner-id custom-workflow)
        owner-project     (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        owner-project-id  (:id owner-project)
        run               (workflows/create-run! database owner-project-id owner-id
                                                  {:workflow-id (:id wf) :mode "manual"})
        run-id            (:id run)
        step-run-id       (:id (first (:steps run)))
        ;; attacker legitimately owns a different project (and a different run on it)
        other-project     (projects-persistence/create-project! database {:user-id other-id :title "Attacker's Project"})
        other-project-id  (:id other-project)
        other-wf          (workflows/create-workflow! database other-id custom-workflow)
        other-run         (workflows/create-run! database other-project-id other-id
                                                  {:workflow-id (:id other-wf) :mode "manual"})]

    (testing "The owner can fetch their own run via their own project"
      (is (match? {:id run-id} (workflows/get-workflow-run database run-id owner-project-id owner-id))))

    (testing "A user who owns a *different* project cannot fetch a foreign run
              by passing their own project-id alongside someone else's run-id"
      (is (nil? (workflows/get-workflow-run database run-id other-project-id other-id))))

    (testing "...nor switch its mode"
      (is (nil? (workflows/update-run-mode! database run-id other-project-id other-id "autonomous")))
      (is (= "manual" (:mode (workflows/get-workflow-run database run-id owner-project-id owner-id)))))

    (testing "...nor read its rounds timeline"
      (is (nil? (workflows/get-run-rounds database run-id other-project-id other-id))))

    (testing "...nor skip one of its steps, even by passing their own (different) run-id"
      (is (nil? (workflows/skip-step! database other-project-id other-id run-id step-run-id))))

    (testing "A user cannot skip a step-run that belongs to a different run than the one they passed,
              even when they own both the run-id and the project"
      (is (nil? (workflows/skip-step! database other-project-id other-id (:id other-run) step-run-id))))))

;; #'workflows/run-parallel-steps! is private - this is a defense-in-depth
;; cap applied at the point Claude calls actually fire (on top of the
;; 20-step/HTTP-schema cap in http_api.workflows/WorkflowRequest), so it's
;; worth verifying directly rather than only through the HTTP layer.
(deftest run-parallel-steps-concurrency-cap-test
  (let [in-flight     (atom 0)
        max-observed  (atom 0)
        fake-executor (reify wf-protocol/IWorkflowExecutor
                        (execute-step! [_ _db _project _step-run _output-stream]
                          (let [n (swap! in-flight inc)]
                            (swap! max-observed max n)
                            (Thread/sleep 20)
                            (swap! in-flight dec)
                            "ok"))
                        (recommend-workflows [_ _project _live-workflows] []))
        step-runs     (mapv (fn [i] {:id i :agent-type "coach"}) (range 25))]
    (#'workflows/run-parallel-steps! fake-executor nil nil step-runs nil)
    (testing "No more than max-concurrent-parallel-steps futures run at once, even with 25 steps"
      (is (<= @max-observed workflows/max-concurrent-parallel-steps)))
    (testing "All steps still complete (batching doesn't drop work)"
      (is (zero? @in-flight)))))

;; find-current-step-run is a plain read with no row lock - this verifies
;; the claim-run!/release-run! guard added to advance-step! actually
;; prevents two overlapping calls on the same run from both executing the
;; pending step (each would otherwise burn its own Claude call).
(deftest advance-step-concurrency-lock-test
  (let [database      (embedded-postgres/fresh-db!)
        user-id       "owner@example.com"
        wf            (workflows/create-workflow! database user-id custom-workflow)
        project       (projects-persistence/create-project! database {:user-id user-id :title "Locked Project"})
        project-id    (:id project)
        run           (workflows/create-run! database project-id user-id
                                             {:workflow-id (:id wf) :mode "manual"})
        run-id        (:id run)
        slow-executor (reify wf-protocol/IWorkflowExecutor
                        (execute-step! [_ _db _project _step-run _output-stream]
                          (Thread/sleep 200)
                          "ok")
                        (recommend-workflows [_ _project _live-workflows] []))
        os1           (java.io.ByteArrayOutputStream.)
        os2           (java.io.ByteArrayOutputStream.)
        f1            (future (workflows/advance-step! database slow-executor project-id user-id run-id os1))]
    (Thread/sleep 50) ;; let f1 claim the run first; it's still sleeping inside execute-step!
    (let [second-result (workflows/advance-step! database slow-executor project-id user-id run-id os2)
          first-result  @f1]
      (testing "The overlapping second call is rejected instead of double-executing the step"
        (is (= {:error :already-advancing} second-result)))
      (testing "The first call proceeds and completes normally"
        (is (not= :already-advancing (:error first-result)))))))

;; run-custom-step! used to call execute-step! directly with no lock at
;; all, so a client firing /advance and /custom-step concurrently on the
;; same run could execute two steps at once - the exact race claim-run!/
;; release-run! exist to close, just through a door that didn't check it.
(deftest run-custom-step-shares-lock-with-advance-step-test
  (let [database      (embedded-postgres/fresh-db!)
        user-id       "owner@example.com"
        wf            (workflows/create-workflow! database user-id custom-workflow)
        project       (projects-persistence/create-project! database {:user-id user-id :title "Locked Project"})
        project-id    (:id project)
        run           (workflows/create-run! database project-id user-id
                                             {:workflow-id (:id wf) :mode "manual"})
        run-id        (:id run)
        slow-executor (reify wf-protocol/IWorkflowExecutor
                        (execute-step! [_ _db _project _step-run _output-stream]
                          (Thread/sleep 200)
                          "ok")
                        (recommend-workflows [_ _project _live-workflows] []))
        os1           (java.io.ByteArrayOutputStream.)
        os2           (java.io.ByteArrayOutputStream.)
        f1            (future (workflows/advance-step! database slow-executor project-id user-id run-id os1))]
    (Thread/sleep 50) ;; let f1 claim the run first; it's still sleeping inside execute-step!
    (let [custom-result (workflows/run-custom-step! database slow-executor project-id user-id run-id
                                                     {:name "Ad-hoc" :description "d"} os2)
          advance-result @f1]
      (testing "A concurrent /custom-step call on the same run is rejected, not raced"
        (is (= {:error :already-advancing} custom-result)))
      (testing "The original /advance call still completes normally"
        (is (not= :already-advancing (:error advance-result)))))))

;; respond-to-step!'s "decision" and sequential-clarify branches append
;; answers to the brief/description with no cap of their own - this is the
;; second, uncapped mutation path for that field (the first being project
;; creation, capped in http_api.projects). Verifies the cap holds here too.
(deftest respond-to-step-description-cap-test
  (let [database       (embedded-postgres/fresh-db!)
        user-id        "owner@example.com"
        wf             (workflows/create-workflow! database user-id custom-workflow)
        project        (projects-persistence/create-project! database {:user-id    user-id
                                                                        :title      "Cap Test"
                                                                        :description "short"})
        project-id     (:id project)
        run            (workflows/create-run! database project-id user-id
                                              {:workflow-id (:id wf) :mode "manual"})
        run-id         (:id run)
        step-run-id    (:id (first (:steps run)))
        no-op-executor (reify wf-protocol/IWorkflowExecutor
                         (execute-step! [_ _db _project _step-run _output-stream] "ok")
                         (recommend-workflows [_ _project _live-workflows] []))]
    (workflows-persistence/update-step-run! database step-run-id
                                            {:status "awaiting_input" :output-kind "clarify"})
    (workflows/respond-to-step! database no-op-executor project-id user-id run-id step-run-id
                                [(apply str (repeat 25000 "a"))])
    (testing "The resulting description is capped, not grown without bound"
      (is (<= (count (:description (projects-persistence/get-project database project-id user-id)))
              workflows/max-description-length)))))
