(ns kaleidoscope.api.interests-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.interests :as interests]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def user-id "reader@example.com")

(deftest create-interest-applies-default-taste-profile-test
  (let [db (embedded-h2/fresh-db!)]
    (testing "an interest created from bare intent gets the full default profile"
      (is (match? {:intent        "Modern jazz history"
                   :taste-profile {:novelty-ratio 0.5 :cadence "weekly" :trusted-sources []}}
                  (interests/create-interest! db user-id {:intent "Modern jazz history"}))))
    (testing "user-supplied fields override defaults without losing the rest"
      (is (match? {:taste-profile {:novelty-ratio 0.8 :cadence "weekly"}}
                  (interests/create-interest! db user-id
                                              {:intent        "Bread baking"
                                               :taste-profile {:novelty-ratio 0.8}}))))))

(deftest update-interest-merges-taste-profile-test
  (let [db       (embedded-h2/fresh-db!)
        interest (interests/create-interest! db user-id
                                             {:intent        "Tech journalism"
                                              :taste-profile {:trusted-sources ["PBS Frontline"]}})]
    (testing "a partial taste-profile edit merges over the stored profile"
      (is (match? {:taste-profile {:novelty-ratio   1.0
                                   :trusted-sources ["PBS Frontline"]
                                   :cadence         "weekly"}}
                  (interests/update-interest! db user-id (:id interest)
                                              {:taste-profile {:novelty-ratio 1.0}}))))
    (testing "non-owners get nil"
      (is (nil? (interests/update-interest! db "attacker@example.com" (:id interest)
                                            {:taste-profile {:novelty-ratio 0.0}}))))))

(deftest shelf-access-is-gated-by-interest-ownership-test
  (let [db       (embedded-h2/fresh-db!)
        interest (interests/create-interest! db user-id {:intent "Tech journalism"})
        [rec]    (recommendations-persistence/create-recommendations!
                  db (:id interest)
                  [{:kind "article" :title "T" :source "S" :url "u"
                    :est-time "5 min" :why "w" :origin "novel"}])]
    (testing "the owner reads the shelf; others get nil (not an empty shelf)"
      (is (= 1 (count (interests/get-shelf db user-id (:id interest) {}))))
      (is (nil? (interests/get-shelf db "attacker@example.com" (:id interest) {}))))
    (testing "status updates are gated the same way"
      (is (nil? (interests/update-recommendation-status!
                 db "attacker@example.com" (:id interest) (:id rec) "queued")))
      (is (match? {:status "queued"}
                  (interests/update-recommendation-status!
                   db user-id (:id interest) (:id rec) "queued"))))))

(deftest fold-refinement-test
  (testing "answers append to :refinements; blanks are dropped; intent untouched"
    (is (= {:intent "x" :refinements ["Only long-form" "No paywalls"]}
           (interests/fold-refinement {:intent "x" :refinements ["Only long-form"]}
                                      ["No paywalls" "" "   "]))))
  (testing "folds into a profile with no :refinements key yet"
    (is (= {:refinements ["a"]} (interests/fold-refinement {} ["a"]))))
  (testing "fold-refinement! persists the fold, scoped to owner"
    (let [db       (embedded-h2/fresh-db!)
          interest (interests/create-interest! db user-id {:intent "Tech journalism"})]
      (is (nil? (interests/fold-refinement! db "attacker@example.com" (:id interest) ["a"])))
      (is (match? {:taste-profile {:refinements ["Prefer primary sources"]}}
                  (interests/fold-refinement! db user-id (:id interest)
                                              ["Prefer primary sources"]))))))
