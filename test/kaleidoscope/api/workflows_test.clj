(ns kaleidoscope.api.workflows-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.workflows :as workflows]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
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
