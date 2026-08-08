(ns kaleidoscope.models.curation)

(def Candidate [:map [:title string?] [:source string?] [:kind string?]
                [:ref [:map [:url string?]]] [:why {:optional true} string?]])

(def Fetched [:map [:status [:enum :ok :dead :error]]
              [:final-url {:optional true} string?] [:raw {:optional true} some?]
              [:content-type {:optional true} string?] [:fetcher {:optional true} keyword?]])

(def Content [:map [:text {:optional true} string?] [:title {:optional true} string?]
              [:author {:optional true} string?] [:available? {:optional true} boolean?]
              [:meta {:optional true} map?]])

(def Verdict [:map [:matches? boolean?]
              [:scores [:map-of keyword? number?]]
              [:composite number?]
              [:why {:optional true} map?] [:est-time {:optional true} string?]
              [:reason {:optional true} string?]])

(def Dimension [:map [:id keyword?] [:label string?] [:weight number?] [:description string?]])

(def Rubric [:map [:dimensions [:vector Dimension]]
             [:pass-line number?] [:aggregate [:enum :weighted-mean]]])

(defn- dim [id label weight description] {:id id :label label :weight weight :description description})

(def ^:private standard-dimensions
  [(dim :relevance "Relevance" 1.0 "How squarely the resource matches the reader's stated intent, 0–10.")
   (dim :depth     "Depth"     1.0 "Substance and rigor of the resource itself, 0–10.")
   (dim :taste-fit "Taste fit" 1.0 "Fit with the reader's keywords, formats, and trusted sources, 0–10.")
   (dim :novelty   "Novelty"   0.5 "How fresh vs. what the reader already sees, 0–10.")])

(def default-rubric
  {"quick"    {:dimensions standard-dimensions :pass-line 5.0 :aggregate :weighted-mean}
   "standard" {:dimensions standard-dimensions :pass-line 7.0 :aggregate :weighted-mean}
   "rigorous" {:dimensions standard-dimensions :pass-line 8.0 :aggregate :weighted-mean}})
