(ns kaleidoscope.api.projects-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.projects :as projects]
            [kaleidoscope.persistence.briefs :as briefs-persistence]
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

(deftest project-update-mass-assignment-test
  ;; Verified exploitable 2026-07-03: update-project! used to pass the raw
  ;; update body straight into the SQL SET clause, including :user-id. A
  ;; caller updating their own project could set :user-id to a victim's
  ;; identity and silently transfer the row — content and all — to that
  ;; victim's account.
  (let [database  (embedded-h2/fresh-db!)
        owner-id  "owner@example.com"
        victim-id "victim@example.com"
        project   (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid       (:id project)]

    (testing "Including :user-id in the update body does not transfer ownership"
      (projects/update-project! database pid owner-id {:title "Still mine" :user-id victim-id})
      (is (some? (projects-persistence/get-project database pid owner-id)))
      (is (nil? (projects-persistence/get-project database pid victim-id))))))

(deftest skill-update-mass-assignment-test
  ;; Verified exploitable 2026-07-03: update-skill! used to pass the raw
  ;; update body straight into the SQL SET clause, even after the WHERE
  ;; clause was fixed to scope by project-id. A caller updating their own
  ;; skill could include :project-id in the body and re-parent the skill
  ;; into a project they don't own.
  (let [database          (embedded-h2/fresh-db!)
        owner-id          "owner@example.com"
        victim-id         "victim@example.com"
        project           (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid               (:id project)
        _                 (projects-persistence/replace-skills! database pid
                                                                 [{:name "Skill 1" :description "d" :position 0}])
        skill-id          (:id (first (projects-persistence/get-skills database pid)))
        victim-project    (projects-persistence/create-project! database {:user-id victim-id :title "Victim's Project"})
        victim-pid        (:id victim-project)]

    (testing "Including :project-id in the update body does not re-parent the skill"
      (projects/update-skill! database pid owner-id skill-id
                              {:name "Still fine" :project-id victim-pid})
      (is (some? (first (filter #(= (:id %) skill-id) (projects-persistence/get-skills database pid)))))
      (is (empty? (projects-persistence/get-skills database victim-pid))))))

(deftest scores-and-briefs-read-ownership-test
  ;; Verified exploitable 2026-07-03: the HTTP handlers for
  ;; GET /projects/:id/scores and GET /projects/:id/briefs[/latest|/:version]
  ;; called persistence functions directly, with no ownership check at
  ;; all — any authenticated writer could read any other user's score runs
  ;; (LLM-generated rationale text) or project briefs (AI-refined project
  ;; descriptions) just by knowing/guessing the project-id. Every sibling
  ;; handler in the same file went through an ownership-checked api/
  ;; function; these three didn't. Fixed by adding get-latest-scores/
  ;; get-all-briefs/get-latest-brief/get-brief-by-version here, matching the
  ;; existing get-score-history pattern, and pointing the HTTP handlers at
  ;; them instead of calling persistence.* directly.
  (let [database  (embedded-h2/fresh-db!)
        owner-id  "owner@example.com"
        other-id  "other@example.com"
        project   (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid       (:id project)
        def1      (projects-persistence/create-score-definition! database
                                                                  {:user-id owner-id :name "D" :description "d"})]
    (projects-persistence/insert-score-run! database pid (:id def1) {:overall 8.5 :dimensions []})
    (briefs-persistence/create-brief! database {:project-id pid :content "Confidential brief" :source "initial"})

    (testing "A different user cannot read another user's latest scores"
      (is (nil? (projects/get-latest-scores database pid other-id))))

    (testing "The owner can read their own latest scores"
      (is (= 1 (count (projects/get-latest-scores database pid owner-id)))))

    (testing "A different user cannot list another user's briefs"
      (is (nil? (projects/get-all-briefs database pid other-id))))

    (testing "A different user cannot read another user's latest brief"
      (is (nil? (projects/get-latest-brief database pid other-id))))

    (testing "A different user cannot read another user's brief by version"
      (is (nil? (projects/get-brief-by-version database pid other-id 1))))

    (testing "The owner can read their own briefs"
      (is (= 1 (count (projects/get-all-briefs database pid owner-id))))
      (is (= "Confidential brief" (:content (projects/get-latest-brief database pid owner-id))))
      (is (= "Confidential brief" (:content (projects/get-brief-by-version database pid owner-id 1)))))))
