(ns kaleidoscope.scoring.agents-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [kaleidoscope.scoring.agents :as agents]))

(deftest librarian-dispatch-test
  (testing "librarian agent-type resolves to the librarian persona (string and keyword)"
    (is (= agents/librarian-system-prompt (agents/get-system-prompt "librarian")))
    (is (= agents/librarian-system-prompt (agents/get-system-prompt :librarian))))
  (testing "existing dispatches are untouched"
    (is (= agents/pm-system-prompt (agents/get-system-prompt "pm")))
    (is (= agents/general-system-prompt (agents/get-system-prompt "unknown-agent")))))

(deftest librarian-prompt-contract-test
  (testing "the persona spells out the JSON candidate contract and the trusted/novel rules"
    (is (str/includes? agents/librarian-system-prompt "\"candidates\""))
    (is (str/includes? agents/librarian-system-prompt "relevance"))
    (is (str/includes? agents/librarian-system-prompt "trusted"))
    (is (str/includes? agents/librarian-system-prompt "est_time"))))

(deftest format-taste-profile-context-test
  (let [ctx (agents/format-taste-profile-context
             {:keywords        ["surveillance" "antitrust"]
              :formats         ["article" "podcast"]
              :lengths         ["under 20 min"]
              :trusted-sources ["PBS Frontline" "The Hill"]
              :novelty-ratio   0.5
              :refinements     ["Prefer primary reporting over commentary"]})]
    (testing "renders every taste-profile field the librarian needs"
      (is (str/includes? ctx "surveillance, antitrust"))
      (is (str/includes? ctx "article, podcast"))
      (is (str/includes? ctx "under 20 min"))
      (is (str/includes? ctx "PBS Frontline, The Hill"))
      (is (str/includes? ctx "0.5"))
      (is (str/includes? ctx "Prefer primary reporting over commentary"))))
  (testing "an empty profile still renders a well-formed block"
    (is (str/includes? (agents/format-taste-profile-context {}) "<taste_profile>"))))
