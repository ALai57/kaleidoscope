(ns kaleidoscope.api.curation
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaleidoscope.api.curation.vetting :as vetting]
            [kaleidoscope.api.interests :as interests-api]
            [kaleidoscope.api.workflows :as workflows-api]
            [kaleidoscope.models.curation :as models.curation]
            [kaleidoscope.persistence.curation :as persistence.curation]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.utils.core :as utils]
            [kaleidoscope.workflows.llm-executor :as llm]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.timbre :as log]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Novelty split (explore/exploit) + relevance threshold
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-shelf-size 6)

(def ^:private relevance-configs
  {"quick"    {:scrutiny "quick"    :relevance-threshold 5.0}
   "standard" {:scrutiny "standard" :relevance-threshold 6.0}
   "rigorous" {:scrutiny "rigorous" :relevance-threshold 7.0}})

(defn relevance-config
  [level]
  (get relevance-configs (or level "standard") (get relevance-configs "standard")))

(defn novelty-quota
  "Split shelf-size slots into {:trusted n :novel m} from the explore dial.
  novelty-ratio 0.0 = all trusted, 1.0 = all novel. The novel share rounds to
  the nearest slot, so 0.5 on an odd shelf gives the extra slot to novel."
  [shelf-size novelty-ratio]
  (let [ratio (-> (double (or novelty-ratio 0.5)) (max 0.0) (min 1.0))
        novel (int (Math/round (* ratio shelf-size)))]
    {:novel novel :trusted (- shelf-size novel)}))

(defn tag-origin
  "Tag each candidate :origin trusted/novel by case-insensitive membership of
  its source in trusted-sources. Origin is decided here, in code — never by
  the LLM — so the \"new source\" tag can't be smuggled or forgotten."
  [candidates trusted-sources]
  (let [trusted? (into #{} (map str/lower-case) (or trusted-sources []))]
    (mapv (fn [{:keys [source] :as candidate}]
            (assoc candidate :origin
                   (if (trusted? (str/lower-case (or source ""))) "trusted" "novel")))
          candidates)))

(defn drop-below-threshold
  "Drop candidates whose relevance is below threshold (missing = 0.0)."
  [candidates threshold]
  (vec (filter #(>= (double (or (:relevance %) 0.0)) (double threshold)) candidates)))

(defn split-candidates
  "Fill the shelf from an origin-tagged pool: the trusted quota from trusted
  candidates (best relevance first), the novel quota from novel candidates,
  then backfill any shortfall from whatever remains so a thin pool still
  fills the shelf as far as it can."
  [candidates {:keys [trusted novel]}]
  (let [by-relevance  (fn [pool] (sort-by #(- (double (or (:relevance %) 0.0))) pool))
        pools         (group-by :origin candidates)
        trusted-picks (vec (take trusted (by-relevance (get pools "trusted"))))
        novel-picks   (vec (take novel (by-relevance (get pools "novel"))))
        picked?       (set (concat trusted-picks novel-picks))
        shortfall     (- (+ trusted novel)
                         (+ (count trusted-picks) (count novel-picks)))
        backfill      (take (max 0 shortfall)
                            (by-relevance (remove picked? candidates)))]
    (vec (concat trusted-picks novel-picks backfill))))

(defn parse-candidates
  "Parse a Discover step's output into candidate maps. The librarian JSON
  contract uses est_time; normalize to :est-time. The output is run through
  extract-json first — the Librarian routinely wraps its object in a ```json
  markdown fence, exactly like the score/decision paths. Returns [] on any
  parse failure — a malformed discovery shelves nothing rather than throwing."
  [output]
  (try
    (let [candidates (->> (:candidates (json/decode (llm/extract-json (or output "")) true))
                          (mapv #(set/rename-keys % {:est_time :est-time})))]
      (when (empty? candidates)
        (log/warnf "Discover step yielded no candidates (output length %d)"
                   (count (or output ""))))
      candidates)
    (catch Exception e
      (log/warnf "Failed to parse Discover output (length %d): %s"
                 (count (or output "")) (.getMessage e))
      [])))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Interest Curation workflow (seeded lazily, like the default workflows)
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def interest-curation-workflow
  {:name        "Interest Curation"
   :description (str "Refine an interest's taste profile if it is too thin, then have the "
                     "Librarian discover and relevance-score candidate resources for its shelf.")
   :is-default  false
   :steps       [{:name        "Refine Interest"
                  :description (str "If the interest's intent is too thin to curate well, ask 1-2 "
                                    "targeted refinement questions. Answers are folded into the "
                                    "taste profile before discovery runs.")
                  :position    0
                  :agent-type  "librarian"
                  :output-kind "clarify"}
                 {:name        "Discover Resources"
                  :description (str "Propose candidate resources across media kinds, honoring the "
                                    "trusted-source allowlist and the novelty dial in the "
                                    "<taste_profile> block. Score each candidate's relevance to the "
                                    "taste profile (0-10) and give each a one-line why.")
                  :position    1
                  :agent-type  "librarian"
                  :output-kind "text"}]})

(defn get-curation-workflow
  [db user-id]
  (when-let [wf (->> (workflows-persistence/get-workflows db user-id)
                     (filter #(= "Interest Curation" (:name %)))
                     first)]
    (workflows-persistence/get-workflow db (:id wf) user-id)))

(defn seed-curation-workflow!
  "Create the Interest Curation workflow for a user if missing, or reconcile
  its steps if the code definition drifted — same lazy-seed pattern as
  api.workflows/seed-default-workflows!."
  [db user-id]
  (if-let [existing (get-curation-workflow db user-id)]
    (let [signature #(select-keys % [:name :position :agent-type :output-kind])
          drifted?  (not= (mapv signature (sort-by :position (:steps existing)))
                          (mapv signature (:steps interest-curation-workflow)))]
      (when drifted?
        (log/infof "Reconciling Interest Curation workflow for user %s" user-id)
        (workflows-persistence/update-workflow! db (:id existing) user-id
                                                {:steps (:steps interest-curation-workflow)}))
      (get-curation-workflow db user-id))
    (do (log/infof "Seeding Interest Curation workflow for user %s" user-id)
        (workflows-persistence/create-workflow! db
                                                (assoc interest-curation-workflow
                                                       :user-id user-id
                                                       :status "live")))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Curation runs: discover → score (threshold) → shelve
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn- sync-backing-project!
  "Write intent + taste-profile context into the backing project description
  so the planner (clarify) and Librarian (discover) prompts see the current
  profile. The mock executor reads the interests row directly instead."
  [db {:keys [project-id user-id intent taste-profile]}]
  (projects-persistence/update-project!
   db project-id user-id
   {:description (str intent "\n\n" (agents/format-taste-profile-context taste-profile))}))

(defn- discover-output
  [run]
  (->> (:steps run)
       (filter #(= "Discover Resources" (:name %)))
       first
       :output))

(defn- ->ref-candidate
  "parse-candidates yields the legacy flat :url shape (the Librarian's JSON
  contract); the vetting pipeline's Candidate contract (shared with the
  grounded discover.clj path) nests it under :ref/:url instead. Adapt here so
  vetting/vet-candidates sees the shape it expects without changing
  parse-candidates' own output."
  [{:keys [url] :as candidate}]
  (-> candidate (assoc :ref {:url url}) (dissoc :url)))

(defn- paused-step
  [run]
  (->> (:steps run)
       (filter #(= "awaiting_input" (:status %)))
       first))

(defn- pending-questions
  [step]
  (let [reply (:reply (try (json/decode (:output step) true)
                           (catch Exception _ nil)))]
    (if reply [reply] [])))

(defn- shelve!
  "The deterministic vet→shelve tail of a curation run: parse the Discover
  output (unchanged — still the workflow-driven librarian text step, not the
  grounded discover.clj path), then run every candidate through the
  fetch→parse→vet gate (vetting/vet-candidates). That gate is what catches
  hallucinated URLs — a candidate whose url 404s or resolves to the wrong
  resource never gets a chance to be tagged trusted/novel. Every quality
  survivor (select-shelf with shelf-size = pool size, so nothing is
  composite-capped yet) is then run through the existing novelty-dial split
  to build the final, size-capped shelf. Only when the final shelf clears
  min-viable is the old shelf archived and the new one persisted — an
  under-viable result (parse failure, everything dead/off-taste/below the
  rubric's pass-line) leaves the existing shelf intact rather than blanking
  the page."
  [db executor interest run]
  (let [taste       (:taste-profile interest)
        run-config  (:config run)
        shelf-size  (or (:shelf-size run-config) default-shelf-size)
        min-viable  (or (:min-viable run-config) 3)
        rubric      (or (get-in interest [:taste-profile :rubric])
                        (get models.curation/default-rubric (:scrutiny run-config "standard")))
        ;; :config round-trips through JSONB, so a :fetch-policy vector
        ;; persisted on the run comes back with string elements ("mock"),
        ;; not keywords (:mock) — re-keywordize so providers/fetch's
        ;; fetchers lookup (keyed by keyword) still resolves. `keyword` is
        ;; idempotent on an already-keyword value.
        vet-config  (merge {:fetch-policy [:anthropic :http] :vet-concurrency 6
                            :api-key (:api-key executor)}
                           (cond-> run-config
                             (seq (:fetch-policy run-config))
                             (update :fetch-policy #(mapv keyword %))))
        discovered  (mapv ->ref-candidate (parse-candidates (discover-output run)))
        pool        (vetting/vet-candidates db (:id run) discovered taste rubric vet-config)
        {:keys [shelved dropped]} (vetting/select-shelf pool rubric (assoc vet-config :shelf-size (count pool)))
        final-url-of (into {} (map (fn [{:keys [candidate fetched]}]
                                     [(get-in candidate [:ref :url]) (:final-url fetched)]))
                           pool)
        ;; Project the final per-candidate vetting outcome to the audit head
        ;; row for every classified candidate — survivors included, not just
        ;; the dead/dropped ones — so curation_run_candidates always reflects
        ;; the terminal verdict regardless of whether the shelf swap fires.
        _           (doseq [c (concat shelved dropped)]
                      (persistence.curation/upsert-candidate!
                       db {:id        (:candidate-id c)
                           :run-id    (:id run)
                           :outcome   (:outcome c)
                           :composite (:composite c)
                           :reason    (:reason c)}))
        final-shelf (-> (mapv #(assoc % :relevance (:composite %)) shelved)
                       (tag-origin (:trusted-sources taste))
                       (split-candidates (novelty-quota shelf-size (:novelty-ratio taste))))
        disposition (if (>= (count final-shelf) min-viable) :swapped :kept-prior)
        recs        (mapv (fn [{:keys [kind title source est-time why origin] :as c}]
                            (let [discovered-url (get-in c [:ref :url])]
                              {:kind     kind
                               :title    title
                               :source   source
                               :url      (or (get final-url-of discovered-url) discovered-url)
                               :est-time est-time
                               :why      why
                               :origin   origin
                               :vetting  {:composite (:composite c) :scores (:scores c) :why (:why c)}}))
                          final-shelf)]
    (when (= :swapped disposition)
      (recommendations-persistence/archive-shelved! db (:id interest))
      (recommendations-persistence/create-recommendations! db (:id interest) recs))
    (log/infof "Curation run %s shelved %d/%d (%d discovered, %d in pool, %d dropped, disposition %s)"
               (:id run) (count recs) shelf-size (count discovered) (count pool) (count dropped) disposition)
    (let [shelved (if (= :swapped disposition) recs [])]
      {:status      "completed"
       :run-id      (:id run)
       :disposition disposition
       :shelved     (vec shelved)
       :summary     {:total   (count shelved)
                     :trusted (count (filter #(= "trusted" (:origin %)) shelved))
                     :novel   (count (filter #(= "novel" (:origin %)) shelved))}})))

(defn- advance-and-shelve!
  "Advance the curation run to completion (or a clarify pause) and shelve the
  results. Shared by run-curation! and respond-to-curation-step!."
  [db executor user-id interest-id run-id]
  (let [interest (interests-persistence/get-interest db interest-id user-id)
        run      (try
                   (workflows-api/advance-step! db executor (:project-id interest)
                                                user-id run-id
                                                (java.io.ByteArrayOutputStream.))
                   ;; A step failure (e.g. an Anthropic 401) is thrown from
                   ;; advance-step! after the run is marked "failed". Turn it
                   ;; into a structured error so the caller sees a real failure
                   ;; instead of an empty shelf.
                   (catch Exception e
                     (log/errorf "Curation run %s failed during advance: %s" run-id (.getMessage e))
                     {:error (.getMessage e)}))]
    (cond
      (:error run)
      (do (log/warnf "Curation run %s returned error: %s" run-id (:error run))
          {:status "error" :error (:error run)})

      (= "awaiting_input" (:status run))
      (let [step (paused-step run)]
        {:status      "awaiting_input"
         :run-id      run-id
         :step-run-id (:id step)
         :questions   (pending-questions step)})

      :else
      (let [fresh-run (workflows-persistence/get-workflow-run db run-id)]
        (if (= "completed" (:status fresh-run))
          (shelve! db executor interest fresh-run)
          (do (log/warnf "Curation run %s ended in non-completed status %s; shelving nothing"
                         run-id (:status fresh-run))
              {:status "error" :run-id run-id}))))))

(defn run-curation!
  "Run the Interest Curation workflow for an interest and refresh its shelf.
  Returns the shelf summary, an awaiting_input pause (clarify questions), or
  nil when the interest isn't owned by user-id."
  [db executor user-id interest-id {:keys [scrutiny shelf-size]}]
  (span/with-span! {:name "kaleidoscope.curation.run"}
    (when-let [interest (interests-persistence/get-interest db interest-id user-id)]
      (seed-curation-workflow! db user-id)
      (sync-backing-project! db interest)
      (let [wf     (get-curation-workflow db user-id)
            config (assoc (relevance-config scrutiny)
                          :shelf-size (or shelf-size default-shelf-size))
            run    (workflows-persistence/create-workflow-run!
                    db (:project-id interest) (:id wf) "autonomous" config)]
        (advance-and-shelve! db executor user-id interest-id (:id run))))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Clarify answers: fold into the taste profile, then resume
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn respond-to-curation-step!
  "Handle a user's answers to a curation clarify step. Unlike the generic
  workflows respond path (which appends answers to project.description and
  resumes in a background future), this folds the answers into the interest's
  editable taste profile, re-syncs the backing project, and resumes the run
  synchronously through to shelving so the caller gets the shelf summary.
  Returns nil unless the interest is owned, the run belongs to the interest's
  backing project, and the step is awaiting input on that run."
  [db executor user-id interest-id run-id step-run-id answers]
  (span/with-span! {:name "kaleidoscope.curation.respond"}
    (when-let [interest (interests-persistence/get-interest db interest-id user-id)]
      (when-let [run (workflows-persistence/get-workflow-run db run-id)]
        (when (= (:project-id run) (:project-id interest))
          (let [step-run (workflows-persistence/get-step-run db step-run-id)]
            (when (and step-run
                       (= run-id (:workflow-run-id step-run))
                       (= "awaiting_input" (:status step-run)))
              (interests-api/fold-refinement! db user-id interest-id answers)
              (sync-backing-project! db (interests-persistence/get-interest db interest-id user-id))
              (workflows-persistence/update-step-run! db step-run-id
                                                      {:status       "completed"
                                                       :completed-at (utils/now)})
              (workflows-persistence/update-workflow-run! db run-id
                                                          {:status       "in_progress"
                                                           :current-step (inc (:current-step run))})
              (advance-and-shelve! db executor user-id interest-id run-id))))))))
