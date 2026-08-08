(ns kaleidoscope.api.curation.vetting)

(defn aggregate
  "Weighted mean of dimension scores per the rubric. Missing score = 0. Σw = 0 -> 0.0."
  [scores {:keys [dimensions]}]
  (let [tw (reduce + 0.0 (map :weight dimensions))]
    (if (zero? tw)
      0.0
      (/ (reduce (fn [acc {:keys [id weight]}]
                   (+ acc (* weight (double (or (get scores id) 0)))))
                 0.0 dimensions)
         tw))))

(defn- classify
  "Attach candidate-level :outcome + :composite to a pool entry, given the rubric."
  [{:keys [candidate fetched verdict]} rubric pass-line]
  (let [composite (when verdict (or (:composite verdict) (aggregate (:scores verdict) rubric)))]
    (cond
      (or (nil? verdict) (= :dead (:status fetched)) (= :error (:status fetched)))
      (assoc candidate :outcome "dead" :reason (or (:reason verdict) "fetch failed"))
      (not (:matches? verdict))
      (assoc candidate :outcome "off-taste" :composite composite :scores (:scores verdict)
             :reason (or (:reason verdict) "wrong resource"))
      (< composite pass-line)
      (assoc candidate :outcome "below-threshold" :composite composite :scores (:scores verdict)
             :reason (or (:reason verdict) "below the bar"))
      :else
      (assoc candidate :outcome "shelved" :composite composite :scores (:scores verdict)
             :matches? true))))

(defn select-shelf
  "PURE. Classify each pool entry, sort survivors by composite desc, take shelf-size,
   decide :swapped vs :kept-prior against the min-viable guard."
  [pool {:keys [pass-line] :as rubric} {:keys [shelf-size min-viable] :or {min-viable 3}}]
  (let [classified (map #(classify % rubric pass-line) pool)
        {shelved "shelved"} (group-by :outcome classified)
        survivors  (->> shelved (sort-by (comp - :composite)) (take shelf-size) vec)]
    {:shelved     survivors
     :dropped     (vec (remove #(= "shelved" (:outcome %)) classified))
     :disposition (if (>= (count survivors) min-viable) :swapped :kept-prior)}))
