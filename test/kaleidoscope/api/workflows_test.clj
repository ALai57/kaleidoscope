(ns kaleidoscope.api.workflows-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.workflows :as workflows]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
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
