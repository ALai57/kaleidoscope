(ns kaleidoscope.workflows.mock-test
  (:require [cheshire.core :as json]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.workflows.mock :as workflow-mock]
            [kaleidoscope.workflows.protocol :as wf-protocol]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(deftest mock-candidates-shape-test
  (let [pool (workflow-mock/mock-candidates {:trusted-sources ["PBS Frontline" "The Hill"]
                                             :formats         ["article" "podcast"]})]
    (testing "3 candidates per trusted source + one per mock novel source"
      (is (= (+ 6 (count workflow-mock/mock-novel-sources)) (count pool))))
    (testing "every candidate satisfies the librarian JSON contract"
      (is (every? #(and (:kind %) (:title %) (:source %) (:url %)
                        (:est_time %) (seq (:why %)) (number? (:relevance %)))
                  pool)))
    (testing "one candidate is deliberately below every relevance threshold"
      (is (some #(< (:relevance %) 5.0) pool)))))

(deftest mock-librarian-discovery-step-test
  (let [db       (tenant/scope (embedded-h2/fresh-db!) "andrewslai.com")
        user-id  "reader@example.com"
        interest (interests-persistence/create-interest!
                  db {:user-id       user-id
                      :intent        "Tech journalism"
                      :taste-profile {:trusted-sources ["PBS Frontline"] :novelty-ratio 0.5}})
        project  {:id (:project-id interest) :user-id user-id :title "Interest: Tech journalism"}
        run      (workflows-persistence/create-workflow-run! db (:project-id interest) nil "manual" {})
        step-run (workflows-persistence/create-custom-step-run!
                  db (:id run) {:name        "Discover Resources"
                                :description "Propose candidates"
                                :agent-type  "librarian"
                                :position    0})
        ;; create-custom-step-run! has no output-kind arg; set it directly
        step-run (workflows-persistence/update-step-run! db (:id step-run) {:output-kind "text"})
        executor (workflow-mock/make-mock-executor)
        output   (wf-protocol/execute-step! executor db project step-run
                                            (java.io.ByteArrayOutputStream.))]
    (testing "the step output is the candidates JSON, drawn from the taste profile"
      (let [candidates (:candidates (json/decode output true))]
        (is (seq candidates))
        (is (some #(= "PBS Frontline" (:source %)) candidates))
        (is (some #(not= "PBS Frontline" (:source %)) candidates))))
    (testing "the step run is completed with the same output persisted"
      (is (match? {:status "completed"}
                  (workflows-persistence/get-step-run db (:id step-run))))
      (is (= output (:output (workflows-persistence/get-step-run db (:id step-run))))))))
