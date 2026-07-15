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

;; description is optional on the wire (ScoreDefinitionRequest marks it
;; {:optional true}) but score_definitions.description is NOT NULL. Without
;; coalescing, an omitted description reaches the INSERT as nil and the DB
;; constraint throws - surfacing as the HTTP 400 the scoring checkly check hit.
(deftest create-score-definition-defaults-missing-description-test
  (let [database (embedded-h2/fresh-db!)
        user-id  "owner@example.com"
        defn     (score-defs/create-score-definition!
                  database user-id (dissoc custom-definition :description))]
    (testing "A definition created without a description is accepted"
      (is (some? (:id defn))))
    (testing "The missing description is stored as an empty string, not nil"
      (is (= "" (:description defn)))
      (is (= "" (:description (score-defs/get-score-definition database user-id (:id defn))))))))

;; score-project!'s unbounded "default definitions" fan-out path only
;; considers is-default=true rows - if a user could set that flag on their
;; own definitions via the HTTP-reachable create path, they could grow that
;; unbounded set arbitrarily. create-score-definition! must force it false
;; regardless of what the caller asks for; only the internal seeding path
;; (which calls the persistence layer directly, bypassing this function) is
;; allowed to create is-default=true rows.
(deftest create-score-definition-forces-is-default-false-test
  (let [database (embedded-h2/fresh-db!)
        user-id  "owner@example.com"
        defn     (score-defs/create-score-definition!
                  database user-id (assoc custom-definition :is-default true))]
    (testing "is-default is false regardless of what the caller sent"
      (is (false? (:is-default (score-defs/get-score-definition database user-id (:id defn))))))))
