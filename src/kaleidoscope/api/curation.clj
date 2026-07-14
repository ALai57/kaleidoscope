(ns kaleidoscope.api.curation
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [kaleidoscope.api.interests :as interests-api]
            [kaleidoscope.api.workflows :as workflows-api]
            [kaleidoscope.persistence.interests :as interests-persistence]
            [kaleidoscope.persistence.projects :as projects-persistence]
            [kaleidoscope.persistence.recommendations :as recommendations-persistence]
            [kaleidoscope.persistence.workflows :as workflows-persistence]
            [kaleidoscope.scoring.agents :as agents]
            [kaleidoscope.utils.core :as utils]
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
  contract uses est_time; normalize to :est-time. Returns [] on any parse
  failure — a malformed discovery shelves nothing rather than throwing."
  [output]
  (try
    (->> (:candidates (json/decode output true))
         (mapv #(set/rename-keys % {:est_time :est-time})))
    (catch Exception _ [])))

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

(defn- pending-questions
  [run]
  (when-let [paused (->> (:steps run)
                         (filter #(= "awaiting_input" (:status %)))
                         first)]
    (let [reply (:reply (try (json/decode (:output paused) true)
                             (catch Exception _ nil)))]
      (if reply [reply] []))))

(defn- shelve!
  "The deterministic score→shelve tail of a curation run: parse the Discover
  output, drop below-threshold candidates, tag + split by the novelty dial,
  archive the old shelf, and persist the new one."
  [db interest run]
  (let [taste      (:taste-profile interest)
        config     (:config run)
        threshold  (or (:relevance-threshold config) 6.0)
        shelf-size (or (:shelf-size config) default-shelf-size)
        selected   (-> (parse-candidates (discover-output run))
                       (drop-below-threshold threshold)
                       (tag-origin (:trusted-sources taste))
                       (split-candidates (novelty-quota shelf-size (:novelty-ratio taste))))
        _          (recommendations-persistence/archive-shelved! db (:id interest))
        shelved    (recommendations-persistence/create-recommendations! db (:id interest) selected)]
    {:status  "completed"
     :run-id  (:id run)
     :shelved shelved
     :summary {:total   (count shelved)
               :trusted (count (filter #(= "trusted" (:origin %)) shelved))
               :novel   (count (filter #(= "novel" (:origin %)) shelved))}}))

(defn- advance-and-shelve!
  "Advance the curation run to completion (or a clarify pause) and shelve the
  results. Shared by run-curation! and respond-to-curation-step!."
  [db executor user-id interest-id run-id]
  (let [interest (interests-persistence/get-interest db interest-id user-id)
        run      (workflows-api/advance-step! db executor (:project-id interest)
                                              user-id run-id
                                              (java.io.ByteArrayOutputStream.))]
    (cond
      (:error run)
      {:status "error" :error (:error run)}

      (= "awaiting_input" (:status run))
      {:status    "awaiting_input"
       :run-id    run-id
       :questions (pending-questions run)}

      :else
      (let [fresh-run (workflows-persistence/get-workflow-run db run-id)]
        (if (= "completed" (:status fresh-run))
          (shelve! db interest fresh-run)
          {:status "error" :run-id run-id})))))

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
