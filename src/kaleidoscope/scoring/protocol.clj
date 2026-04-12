(ns kaleidoscope.scoring.protocol)

(defprotocol IScorer
  (score [this project score-definition]
    "Score a project against a score definition.

     score-definition: {:name        str
                        :description str
                        :scorer-type keyword
                        :dimensions  [{:name str :criteria str}]}

     project: {:title str :description str}

     Returns: {:overall    n
               :dimensions [{:name str :value n :rationale str}]}"))
