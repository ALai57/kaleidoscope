(ns kaleidoscope.api.tasks-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.tasks :as tasks]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tasks :as tasks-persistence]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(defn- new-project!
  [db user-id title]
  (projects-persistence/create-project! db {:user-id user-id :title title}))

(deftest task-project-ownership-test
  (let [database (embedded-h2/fresh-db!)
        owner-id "owner@example.com"
        other-id "other@example.com"
        ;; owner's project + task
        project  (new-project! database owner-id "Owner's Project")
        project-id (:id project)
        task     (tasks/create-task! database project-id owner-id {:title "Owner's Task"})
        task-id  (:id task)
        ;; attacker's own project (they own this one legitimately)
        other-project (new-project! database other-id "Attacker's Project")
        other-project-id (:id other-project)]

    (testing "The owner can update their own task via their own project"
      (is (match? {:title "Renamed"}
                  (tasks/update-task! database project-id owner-id task-id {:title "Renamed"}))))

    (testing "A user who owns a *different* project cannot update a foreign task
              by passing their own project-id alongside someone else's task-id"
      (is (nil? (tasks/update-task! database other-project-id other-id task-id {:title "Hijacked"})))
      (is (= "Renamed" (:title (tasks-persistence/get-task database task-id)))))

    (testing "A user who owns a different project cannot delete a foreign task"
      (is (nil? (tasks/delete-task! database other-project-id other-id task-id)))
      (is (some? (tasks-persistence/get-task database task-id))))

    (testing "Bulk reorder rejects the whole batch if any id doesn't belong to the project"
      (is (nil? (tasks/reorder-tasks! database other-project-id other-id
                                      [{:id task-id :position 5}])))
      (is (= 0 (:position (tasks-persistence/get-task database task-id)))))

    (testing "The owner can still reorder their own task"
      (is (some? (tasks/reorder-tasks! database project-id owner-id
                                       [{:id task-id :position 3}])))
      (is (= 3 (:position (tasks-persistence/get-task database task-id)))))

    (testing "The owner can delete their own task"
      (is (some? (tasks/delete-task! database project-id owner-id task-id)))
      (is (nil? (tasks-persistence/get-task database task-id))))))

(deftest task-update-mass-assignment-test
  ;; Verified exploitable 2026-07-03: update-task! used to pass the raw
  ;; update body straight into the SQL SET clause. A caller who legitimately
  ;; owns a task could include :project-id in the body and re-parent their
  ;; own task into a project they don't own — the preceding ownership check
  ;; only verifies the task's *current* project-id, not what the update
  ;; itself is trying to set. This plants attacker-controlled content
  ;; (title/description) into a victim's project without ever touching it
  ;; directly.
  (let [database          (embedded-h2/fresh-db!)
        owner-id          "owner@example.com"
        victim-id         "victim@example.com"
        project           (new-project! database owner-id "Owner's Project")
        project-id        (:id project)
        task              (tasks/create-task! database project-id owner-id {:title "Legit task"})
        task-id           (:id task)
        victim-project    (new-project! database victim-id "Victim's Project")
        victim-project-id (:id victim-project)]

    (testing "Including :project-id in the update body does not re-parent the task"
      (tasks/update-task! database project-id owner-id task-id
                          {:title "Still fine" :project-id victim-project-id})
      (is (= project-id (:project-id (tasks-persistence/get-task database task-id))))
      (is (empty? (tasks-persistence/list-tasks database victim-project-id))))))
