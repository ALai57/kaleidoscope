(ns kaleidoscope.api.agents-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.agents :as agents]
            [kaleidoscope.persistence.agents :as agents-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

;; Reads go through kaleidoscope.persistence.agents directly rather than
;; kaleidoscope.api.agents/get-agent-definitions, which also seeds the
;; default agent definitions on every call — unrelated to ownership, and
;; that seeding path has a pre-existing H2 incompatibility (raw `ON
;; CONFLICT` SQL) that isn't this test's concern.
(deftest agent-definition-ownership-test
  (let [database (embedded-h2/fresh-db!)
        owner-id "owner@example.com"
        other-id "other@example.com"
        defn     (agents/create-agent-definition! database owner-id
                                                   {:agent-type    "custom-agent"
                                                    :name          "Custom Coach"
                                                    :avatar        "X"
                                                    :system-prompt "Be helpful."})
        def-id   (:id defn)]

    (testing "A different user cannot update someone else's agent definition"
      (is (nil? (agents/update-agent-definition! database other-id def-id {:name "Hijacked"})))
      (is (= "Custom Coach" (:name (first (agents-persistence/get-agent-definitions database owner-id))))))

    (testing "The owner can update their own agent definition"
      (is (match? {:name "Renamed"}
                  (agents/update-agent-definition! database owner-id def-id {:name "Renamed"}))))))
