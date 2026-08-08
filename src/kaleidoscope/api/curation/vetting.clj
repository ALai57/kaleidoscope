(ns kaleidoscope.api.curation.vetting
  (:require [kaleidoscope.api.curation.providers :as providers]
            [kaleidoscope.api.curation.vet :as vet]
            [kaleidoscope.persistence.curation :as persistence.curation]
            [kaleidoscope.utils.core :as utils]))

(def ^:private default-model
  "claude-opus-4-6")

(def ^:private prompt-version
  "v1")

(def ^:private default-vet-concurrency
  6)

(defn store-raw!
  "Content-address a fetched raw blob to the object store. DEFERRED: this is
  a no-op stub — it never writes anywhere — so vet-candidates doesn't block
  on the object-store integration (plans/2026-07-18-object-storage-model).
  Returns a descriptor recorded in the fetch stage-run's provenance under
  :raw-blob; a real implementation swaps this fn for one that actually
  writes and returns a live :storage-ref."
  [raw]
  {:storage-ref nil :sha256 (str (hash raw)) :size (count (str raw))})

(defn- stage-provenance
  "Common provenance stamped onto every stage-run: the fetcher that resolved
  this candidate, the model/prompt identity, and immutable snapshots of the
  taste profile + rubric in force when this run executed — so a stage-run
  row is self-describing even after the taste profile or rubric changes.
  Timestamps are stringified: :provenance is stored as JSON(B), and cheshire
  has no built-in encoder for java.time.LocalDateTime nested in a map."
  [{:keys [fetcher taste rubric started-at]}]
  {:fetcher fetcher
   :model-id default-model
   :prompt-version prompt-version
   :taste-profile-snapshot taste
   :rubric-snapshot rubric
   :started-at (str started-at)
   :finished-at (str (utils/now))})

(defn- process-candidate
  "fetch -> parse -> vet for one candidate, persisting each stage and
  projecting the head incrementally. Returns the pool entry select-shelf
  consumes: {:candidate :fetched :content :verdict}. A non-:ok fetch
  short-circuits before parse/vet, with :verdict nil and the head
  re-projected as a terminal `dead` outcome."
  [db run-id taste rubric config candidate]
  (let [{candidate-id :id} (persistence.curation/upsert-candidate!
                             db {:run-id run-id :ref (:ref candidate)
                                 :kind   (:kind candidate) :source (:source candidate)})
        fetch-started (utils/now)
        fetched       (providers/fetch (:ref candidate) config)
        fetcher       (:fetcher fetched)]
    (let [raw-blob-stub (store-raw! (:raw fetched))]
      (persistence.curation/record-stage!
       db {:run-id run-id :candidate-id candidate-id :stage "fetch" :attempt 1
           :status (if (= :ok (:status fetched)) "ok" "error")
           :input  (:ref candidate)
           :output (assoc fetched :raw raw-blob-stub)
           :provenance (assoc (stage-provenance {:fetcher fetcher :taste taste :rubric rubric
                                                  :started-at fetch-started})
                               :raw-blob raw-blob-stub)}))
    (if (not= :ok (:status fetched))
      (do
        (persistence.curation/upsert-candidate!
         db {:id candidate-id :run-id run-id :ref (:ref candidate) :kind (:kind candidate)
             :source (:source candidate) :fetcher fetcher
             :outcome "dead" :reason "fetch failed"})
        {:candidate candidate :fetched fetched :verdict nil})
      (let [parse-started (utils/now)
            content       (providers/parse fetched)
            _             (persistence.curation/record-stage!
                           db {:run-id run-id :candidate-id candidate-id :stage "parse" :attempt 1
                               :status "ok"
                               :input  (dissoc fetched :raw)
                               :output content
                               :provenance (stage-provenance {:fetcher fetcher :taste taste :rubric rubric
                                                               :started-at parse-started})})
            vet-started   (utils/now)
            vet-content   (-> content
                              (update :title #(or % (:title candidate)))
                              (assoc :claimed-title (:title candidate) :claimed-source (:source candidate)))
            verdict       (vet/vet vet-content taste rubric config)
            _             (persistence.curation/record-stage!
                           db {:run-id run-id :candidate-id candidate-id :stage "vet" :attempt 1
                               :status "ok"
                               :input  vet-content
                               :output verdict
                               :provenance (stage-provenance {:fetcher fetcher :taste taste :rubric rubric
                                                               :started-at vet-started})})]
        (persistence.curation/upsert-candidate!
         db {:id candidate-id :run-id run-id :ref (:ref candidate) :kind (:kind candidate)
             :source (:source candidate) :fetcher fetcher :final-url (:final-url fetched)
             :matches? (:matches? verdict) :scores (:scores verdict)})
        {:candidate candidate :fetched fetched :content content :verdict verdict}))))

(defn vet-candidates
  "Bounded-concurrent fetch -> parse -> vet over `candidates`. Partitions into
  chunks of (:vet-concurrency config 6) and runs each chunk with pmap, so at
  most :vet-concurrency candidates are in flight at once (no claypoole dep).
  Persists every stage as an append-only curation_stage_runs row and projects
  the per-candidate head via upsert-candidate! as it advances. Returns the
  pool select-shelf consumes: [{:candidate :fetched :content :verdict}]."
  [db run-id candidates taste rubric config]
  (->> candidates
       (partition-all (:vet-concurrency config default-vet-concurrency))
       (mapcat (fn [chunk] (doall (pmap #(process-candidate db run-id taste rubric config %) chunk))))
       vec))

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
  (let [composite (when verdict (aggregate (:scores verdict) rubric))]
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
