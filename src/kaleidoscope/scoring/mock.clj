(ns kaleidoscope.scoring.mock
  (:require [kaleidoscope.scoring.protocol :as protocol]))

(defrecord MockScorer []
  protocol/IScorer
  (score [_this _project score-definition]
    (let [dimensions (:dimensions score-definition)
          dim-results (mapv (fn [{:keys [name]}]
                              {:dimension-name name
                               :value          5.0
                               :rationale      "Mock score: all dimensions default to 5.0"})
                            dimensions)]
      {:overall    5.0
       :dimensions dim-results})))

(defn make-mock-scorer
  []
  (->MockScorer))

(comment
  (def mock (make-mock-scorer))

  (protocol/score mock
                  {:title "My project" :description "A cool project"}
                  {:name        "Intent Clarity"
                   :description "Evaluates intent"
                   :scorer-type "pm"
                   :dimensions  [{:name "Problem Clarity" :criteria "Is the problem clear?"}
                                 {:name "User Behaviors"  :criteria "Are user behaviors described?"}]}))
