(ns kaleidoscope.api.score-definitions-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [kaleidoscope.api.score-definitions :as score-defs]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [matcher-combinators.test :refer [match?]]
            [taoensso.timbre :as log]))

(use-fixtures :once
  (fn [f]
    (log/with-min-level :error
      (f))))

(def custom-definition
  {:name        "Custom Definition"
   :description "A user-authored score definition"
   :scorer-type "general"
   :is-default  false
   :dimensions  [{:name "Clarity" :criteria "Is it clear?"}]})

(deftest score-definition-ownership-test
  (let [database (embedded-h2/fresh-db!)
        owner-id "owner@example.com"
        other-id "other@example.com"
        defn     (score-defs/create-score-definition! database owner-id custom-definition)
        def-id   (:id defn)]

    (testing "The owner can fetch their score definition"
      (is (match? {:id def-id :name "Custom Definition"}
                  (score-defs/get-score-definition database owner-id def-id))))

    (testing "A different user cannot fetch someone else's score definition"
      (is (nil? (score-defs/get-score-definition database other-id def-id))))

    (testing "A different user cannot update someone else's score definition"
      (is (nil? (score-defs/update-score-definition! database other-id def-id {:name "Hijacked"})))
      (is (= "Custom Definition" (:name (score-defs/get-score-definition database owner-id def-id)))))

    (testing "The owner can update their own score definition"
      (is (match? {:name "Renamed"}
                  (score-defs/update-score-definition! database owner-id def-id {:name "Renamed"}))))

    (testing "A different user cannot delete someone else's score definition"
      (is (match? {:error :not-found}
                  (score-defs/delete-score-definition! database other-id def-id)))
      (is (some? (score-defs/get-score-definition database owner-id def-id))))

    (testing "The owner can delete their own score definition"
      (is (not (:error (score-defs/delete-score-definition! database owner-id def-id))))
      (is (nil? (score-defs/get-score-definition database owner-id def-id))))))
