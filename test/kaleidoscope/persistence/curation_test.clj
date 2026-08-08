(ns kaleidoscope.persistence.curation-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.curation :as curation]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.utils.core :as utils]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest record-stage-and-latest-ok-stage-test
  (testing "latest-ok-stage returns the highest-attempt `ok` row for (run, candidate, stage)"
    (let [db           (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
          run-id       (utils/uuid)
          candidate-id (utils/uuid)]
      (curation/record-stage! db {:run-id run-id :candidate-id candidate-id
                                   :stage "vet" :attempt 1 :status "ok"
                                   :input {:content "first pass"} :output {:composite 4.0}
                                   :provenance {:model-id "claude-opus-4-6"}})
      (curation/record-stage! db {:run-id run-id :candidate-id candidate-id
                                   :stage "vet" :attempt 2 :status "ok"
                                   :input {:content "second pass"} :output {:composite 8.0}
                                   :provenance {:model-id "claude-opus-4-6"}})

      (is (match? {:attempt 2 :status "ok" :output {:composite 8.0}}
                  (curation/latest-ok-stage db run-id candidate-id "vet"))))

    (testing "no matching stage returns nil"
      (let [db (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")]
        (is (nil? (curation/latest-ok-stage db (utils/uuid) (utils/uuid) "vet")))))))

(deftest upsert-candidate-round-trips-matches-test
  (testing "upsert-candidate! with :matches? true round-trips through the `matches` column"
    (let [db     (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
          run-id (utils/uuid)
          head   (curation/upsert-candidate! db {:run-id   run-id
                                                   :ref      {:url "https://example.com/a"}
                                                   :kind     "article"
                                                   :source   "PBS Frontline"
                                                   :fetcher  "homespun-http"
                                                   :final-url "https://example.com/a"
                                                   :matches? true
                                                   :scores   {:relevance 8}
                                                   :composite 8.0
                                                   :outcome  "shelved"
                                                   :reason   nil})]
      (is (true? (:matches? head)))
      (is (match? [{:matches? true :outcome "shelved" :run-id run-id}]
                  (curation/run-candidates db run-id)))

      (testing "passing :id back updates the existing head row in place"
        (let [updated (curation/upsert-candidate! db (assoc head :outcome "dead" :matches? false))]
          (is (= (:id head) (:id updated)))
          (is (false? (:matches? updated)))
          (is (= 1 (count (curation/run-candidates db run-id))))
          (is (match? [{:outcome "dead" :matches? false}]
                      (curation/run-candidates db run-id)))

          (testing "re-projecting a head preserves its original :created-at"
            (is (= (:created-at head) (:created-at updated)))))))))

(deftest record-feedback-idempotent-toggle-test
  (testing "re-posting the same (candidate, kind) toggles active -> withdrawn"
    (let [db          (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
          interest    (interests-persistence/create-interest!
                       db {:user-id "reader@example.com" :intent "Tech journalism" :taste-profile {}})
          interest-id (:id interest)
          run-id      (utils/uuid)
          candidate-id (utils/uuid)
          first-post  (curation/record-feedback! db {:interest-id interest-id :run-id run-id
                                                       :candidate-id candidate-id
                                                       :kind "more" :directive "single-author focus books"})]
      (is (match? {:status "active" :kind "more"} first-post))
      (is (match? [{:id (:id first-post) :status "active"}]
                  (curation/active-feedback db interest-id)))

      (let [second-post (curation/record-feedback! db {:interest-id interest-id :run-id run-id
                                                         :candidate-id candidate-id
                                                         :kind "more" :directive "single-author focus books"})]
        (is (= (:id first-post) (:id second-post)))
        (is (match? {:status "withdrawn"} second-post))
        (is (= [] (curation/active-feedback db interest-id)))))))
