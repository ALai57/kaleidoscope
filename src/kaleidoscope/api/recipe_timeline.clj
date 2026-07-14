(ns kaleidoscope.api.recipe-timeline
  "Cook-timeline domain logic: pure scheduling + fingerprint/merge, plus the
  `generate!` orchestration over a pluggable timeline generator. No HTTP; no SQL
  beyond what callers pass in. See plans/2026-07-14-recipe-cook-timeline/DESIGN.md."
  (:require [clojure.string :as str]
            [kaleidoscope.timeline.protocol :as protocol])
  (:import [java.security MessageDigest]))

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

(defn component-id
  "A component's stable id (lane label): its :name, else 1-based ordinal.
  A nil, empty, or whitespace-only name falls back to the ordinal."
  [section index]
  (if (str/blank? (:name section))
    (str "Section " (inc index))
    (:name section)))

(defn steps-hash
  "Hex SHA-256 of a component's steps — the content fingerprint that decides
  whether a component must be re-segmented."
  [steps]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (str/join "\n" steps)
         (.getBytes)
         (.digest md)
         (map #(format "%02x" %))
         (apply str))))

(defn content-fingerprint
  "[{:id :steps-hash}] in section order."
  [content]
  (vec (map-indexed (fn [i s] {:id (component-id s i) :steps-hash (steps-hash (:steps s))})
                    (:sections content))))

(defn changed-ids
  "Component-ids whose steps differ from `stored` (all when stored has no
  components — a first generation or a wiped cache)."
  [content stored]
  (let [prev (into {} (map (juxt :name :steps-hash)) (:components stored))]
    (into #{} (comp (filter (fn [{:keys [id steps-hash]}]
                              (not= steps-hash (get prev id))))
                    (map :id))
          (content-fingerprint content))))

(defn resolve-deps
  "Drop each phase's deps that don't name an existing phase across `components`."
  [components]
  (let [ids (into #{} (for [c components p (:phases c)] (:id p)))]
    (mapv (fn [c] (update c :phases
                          (fn [ps] (mapv #(update % :deps (fn [ds] (filterv ids ds))) ps))))
          components)))

(defn assemble
  "Final components: cached phases for unchanged components (authoritative — the
  generator's re-segmentation of them is ignored), `proposal` phases for changed
  ones. `:steps-hash` is refreshed from current content. `proposal` and `stored`
  are keyed by component name."
  [content proposal stored changed]
  (let [fp        (content-fingerprint content)
        cached    (into {} (map (juxt :name identity)) (:components stored))
        proposed  (into {} (map (juxt :name identity)) (:components proposal))]
    (mapv (fn [{:keys [id steps-hash]}]
            (let [phases (if (contains? changed id)
                           (:phases (get proposed id))
                           (:phases (get cached id)))]
              {:name id :steps-hash steps-hash :phases (vec phases)}))
          fp)))

(defn surviving-overrides
  "Overrides whose component (the id before the first '/') is not in `changed`."
  [stored changed]
  (filterv (fn [{:keys [phase]}]
             (not (contains? changed (first (str/split phase #"/" 2)))))
           (:overrides stored)))

(defn with-overrides
  "Replace a timeline's overrides and re-pack (pure; no generator)."
  [timeline overrides]
  (let [packed (pack (:components timeline) overrides)]
    (assoc timeline
           :overrides (vec overrides)
           :components (:components packed)
           :total-minutes (:total-minutes packed))))

(def default-generator-version 1)

(defn generate!
  "(Re)generate a recipe's timeline. Per-component: unchanged components keep
  their cached phases (the generator's re-segmentation of them is discarded);
  changed components get fresh phases and lose their overrides. Short-circuits
  when nothing changed and the generator-version is current."
  [{:keys [generator content stored generator-version now]}]
  (let [changed (changed-ids content stored)]
    (if (and (empty? changed)
             stored
             (= generator-version (:generator-version stored)))
      stored
      (let [proposal   (protocol/segment generator {:content content} changed (:components stored))
            components  (-> (assemble content proposal stored changed) resolve-deps)
            overrides   (surviving-overrides stored changed)
            packed      (pack components overrides)]
        {:version           1
         :generator-version generator-version
         :generated-at      now
         :total-minutes     (:total-minutes packed)
         :overrides         (vec overrides)
         :components        (:components packed)}))))
