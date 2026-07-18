(ns kaleidoscope.persistence.ownership-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.themes :as themes-api]
            [kaleidoscope.persistence.ownership :as ownership]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.rdbms.embedded-postgres-impl :as embedded-postgres]
            [kaleidoscope.persistence.tenant :as tenant]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest get-owned-single-column-test
  (let [database (tenant/scope (embedded-postgres/fresh-db!) "andrewslai.com")
        owner-id "owner@example.com"
        other-id "other@example.com"
        project  (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid      (:id project)]

    (testing "the owner can fetch their own row"
      (is (match? {:id pid :title "Owner's Project"}
                  (ownership/get-owned database :projects pid owner-id))))

    (testing "a different user cannot fetch it — same nil as not-found"
      (is (nil? (ownership/get-owned database :projects pid other-id))))

    (testing "a nonexistent id returns nil regardless of owner"
      (is (nil? (ownership/get-owned database :projects (random-uuid) owner-id))))))

(deftest update-owned-and-delete-owned-single-column-test
  (let [database (tenant/scope (embedded-postgres/fresh-db!) "andrewslai.com")
        owner-id "owner@example.com"
        other-id "other@example.com"
        project  (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid      (:id project)]

    (testing "a non-owner's update is silently a no-op — no row matches the WHERE clause"
      (is (nil? (ownership/update-owned! database :projects pid other-id {:title "Hijacked"})))
      (is (= "Owner's Project" (:title (ownership/get-owned database :projects pid owner-id)))))

    (testing "the owner's update succeeds"
      (is (match? {:title "Renamed"}
                  (ownership/update-owned! database :projects pid owner-id {:title "Renamed"}))))

    (testing "a non-owner's delete is a no-op and returns false"
      (is (false? (ownership/delete-owned! database :projects pid other-id)))
      (is (some? (ownership/get-owned database :projects pid owner-id))))

    (testing "the owner's delete succeeds and returns true"
      (is (true? (ownership/delete-owned! database :projects pid owner-id)))
      (is (nil? (ownership/get-owned database :projects pid owner-id))))))

(deftest scoped-update-works-on-h2-too-test
  ;; rdbms/scoped-update-impl! is a multimethod dispatched on connection
  ;; class — Postgres and H2 need different SQL for "UPDATE ... RETURNING"
  ;; (H2 has no RETURNING; it needs `SELECT * FROM FINAL TABLE (...)`, same
  ;; as the existing update-impl! override). The single-column tests above
  ;; only exercise Postgres; this catches the H2 dialect gap on its own.
  (let [database (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        owner-id "owner@example.com"
        other-id "other@example.com"
        project  (projects-persistence/create-project! database {:user-id owner-id :title "Owner's Project"})
        pid      (:id project)]

    (testing "a non-owner's update is a no-op on H2 too"
      (is (nil? (ownership/update-owned! database :projects pid other-id {:title "Hijacked"})))
      (is (= "Owner's Project" (:title (ownership/get-owned database :projects pid owner-id)))))

    (testing "the owner's update succeeds on H2"
      (is (match? {:title "Renamed"}
                  (ownership/update-owned! database :projects pid owner-id {:title "Renamed"}))))

    (testing "the owner's delete succeeds on H2"
      (is (true? (ownership/delete-owned! database :projects pid owner-id)))
      (is (nil? (ownership/get-owned database :projects pid owner-id))))))

(deftest compound-site-scoped-resource-test
  (let [database   (embedded-postgres/fresh-db!)
        owner-id   "owner@example.com"
        [theme]    (themes-api/create-theme! database {:display-name "Owner's Theme"
                                                        :config       {:primary {:main "#000"}}
                                                        :owner-id     owner-id
                                                        :hostname     "andrewslai.com"})
        theme-id   (:id theme)]

    (testing "fetching with the correct owner AND site succeeds"
      (is (match? {:id theme-id}
                  (ownership/get-owned database :themes theme-id owner-id "andrewslai.com"))))

    (testing "the same owner, wrong site, is treated as not found — this is the fix for the
              cross-site theme gap: a multi-site writer can't touch a theme belonging to a
              site they didn't authorize the request against"
      (is (nil? (ownership/get-owned database :themes theme-id owner-id "sahiltalkingcents.com"))))

    (testing "update respects the compound key the same way"
      (is (nil? (ownership/update-owned! database :themes theme-id owner-id
                                         {:display-name "Hijacked"} "sahiltalkingcents.com")))
      (is (match? {:display-name "Renamed"}
                  (ownership/update-owned! database :themes theme-id owner-id
                                           {:display-name "Renamed"} "andrewslai.com"))))))
