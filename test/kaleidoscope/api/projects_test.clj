(ns kaleidoscope.api.projects-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.projects :as projects]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest project-ownership-test
  ;; This is the original motivating bug for the ownership-consolidation
  ;; plan: update-project!/delete-project! used to accept a user-id
  ;; argument and silently ignore it, so ownership was enforced only by a
  ;; preceding get-project call a future caller could forget. Now scoped by
  ;; rdbms/scoped-update!/scoped-delete! directly.
  (let [database (embedded-h2/fresh-db!)
        owner-id "owner@example.com"
        other-id "other@example.com"
        project  (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid      (:id project)]

    (testing "the owner can update their own project"
      (is (match? {:title "Renamed"}
                  (projects/update-project! database pid owner-id {:title "Renamed"}))))

    (testing "a different user cannot update someone else's project"
      (is (nil? (projects/update-project! database pid other-id {:title "Hijacked"})))
      (is (= "Renamed" (:title (projects-persistence/get-project database pid owner-id)))))

    (testing "a different user cannot delete someone else's project"
      (projects/delete-project! database pid other-id)
      (is (some? (projects-persistence/get-project database pid owner-id))))

    (testing "the owner can delete their own project"
      (projects/delete-project! database pid owner-id)
      (is (nil? (projects-persistence/get-project database pid owner-id))))))

(deftest skill-project-scoping-test
  ;; persistence/update-skill! used to accept project-id and silently ignore
  ;; it (the same Pattern A shape as update-project!, just not previously
  ;; catalogued), so a caller who owns *any* project could pass their own
  ;; project-id alongside another project's skill-id and mutate it.
  (let [database          (embedded-h2/fresh-db!)
        owner-id          "owner@example.com"
        other-id          "other@example.com"
        project           (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid               (:id project)
        _                 (projects-persistence/replace-skills! database pid
                                                                 [{:name "Skill 1" :description "d" :position 0}])
        skill-id          (:id (first (projects-persistence/get-skills database pid)))
        other-project     (projects-persistence/create-project! database {:user-id other-id :title "Attacker's Project"})
        other-pid         (:id other-project)]

    (testing "the owner can update their own skill via their own project"
      (projects/update-skill! database pid owner-id skill-id {:name "Renamed"})
      (is (= "Renamed" (:name (first (projects-persistence/get-skills database pid))))))

    (testing "a user who owns a *different* project cannot update a foreign skill
              by passing their own project-id alongside someone else's skill-id"
      (projects/update-skill! database other-pid other-id skill-id {:name "Hijacked"})
      (is (= "Renamed" (:name (first (projects-persistence/get-skills database pid))))))))
