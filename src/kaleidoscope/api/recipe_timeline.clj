(ns kaleidoscope.api.recipe-timeline
  "Cook-timeline domain logic: pure scheduling + fingerprint/merge, plus the
  `generate!` orchestration over a pluggable timeline generator. No HTTP; no SQL
  beyond what callers pass in. See plans/2026-07-14-recipe-cook-timeline/DESIGN.md.")

(defn- topo-sort
  "Phase ids in dependency order, authored order as the tiebreak. Deps to
  unknown ids are ignored; a cycle is broken by emitting the next remaining id."
  [phases]
  (let [ids     (mapv :id phases)
        idset   (set ids)
        deps-of (into {} (map (fn [p] [(:id p) (filterv idset (:deps p))])) phases)]
    (loop [remaining ids, placed #{}, out []]
      (if (empty? remaining)
        out
        (let [pick (or (first (filter #(every? placed (deps-of %)) remaining))
                       (first remaining))]     ;; cycle guard
          (recur (filterv #(not= % pick) remaining) (conj placed pick) (conj out pick)))))))

(defn pack
  "Assign :start to every phase across `components`. Active phases serialize on a
  single cook; passive phases float as early as their deps allow. Effective
  duration = matching override :minutes, else :estimate. Returns
  {:components <with :start> :total-minutes int}."
  [components overrides]
  (let [ov-by-id (into {} (map (juxt :phase :minutes)) overrides)
        phases   (for [c components p (:phases c)]
                   (assoc p :duration (or (ov-by-id (:id p)) (:estimate p))))
        by-id    (into {} (map (juxt :id identity)) phases)
        order    (topo-sort (vec phases))
        {:keys [spans]}
        (reduce (fn [{:keys [spans cook-free]} id]
                  (let [{:keys [kind duration deps]} (by-id id)
                        ready (reduce (fn [m d] (max m (get-in spans [d :end] 0))) 0
                                      (filter by-id deps))
                        start (if (= "active" kind) (max ready cook-free) ready)
                        end   (+ start duration)]
                    {:spans     (assoc spans id {:start start :end end})
                     :cook-free (if (= "active" kind) end cook-free)}))
                {:spans {} :cook-free 0}
                order)
        total (reduce (fn [m s] (max m (:end s))) 0 (vals spans))]
    {:components    (mapv (fn [c]
                            (update c :phases
                                    (fn [ps] (mapv #(assoc % :start (get-in spans [(:id %) :start])) ps))))
                          components)
     :total-minutes total}))
