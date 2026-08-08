(ns kaleidoscope.persistence.curation
  "Persistence for the Librarian grounded-vetting pipeline: the append-only
  per-stage trace (`curation_stage_runs`), the per-candidate head projection
  (`curation_run_candidates`), and the reader feedback stream
  (`curation_feedback`). All tenant-scoped via the `db` handle — see
  `persistence/recommendations.clj` for the hand-written HoneySQL pattern
  this mirrors."
  (:require [clojure.set :as set]
            [honey.sql :as hsql]
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.tenant :as tenant]
            [kaleidoscope.utils.core :as utils]
            [next.jdbc :as next]
            [next.jdbc.result-set :as rs]))

;; The `matches` SQL column can't be named `matches?` — Clojure keys keep the
;; `?` since it reads as a predicate; rename explicitly on the way in and out.
(defn- ->sql-row [m]
  (set/rename-keys m {:matches? :matches}))

(defn- ->clj-row [row]
  (when row
    (set/rename-keys row {:matches :matches?})))

(defn record-stage!
  "Insert one append-only row into `curation_stage_runs`. A re-run of a stage
  appends a new `:attempt` — never overwrites a prior one."
  [db {:keys [run-id candidate-id stage attempt status input output provenance]}]
  (first (rdbms/insert! db
                        :curation-stage-runs
                        {:id           (utils/uuid)
                         :run-id       run-id
                         :candidate-id candidate-id
                         :stage        stage
                         :attempt      attempt
                         :status       status
                         :input        input
                         :output       output
                         :provenance   (or provenance {})
                         :created-at   (utils/now)}
                        :ex-subtype :UnableToRecordCurationStage)))

(defn latest-ok-stage
  "The highest-`attempt` row for `(run-id, candidate-id, stage)` with
  `status = 'ok'`, or nil. This is the 'current value' for that stage —
  a re-run over a prior attempt is directly comparable to it."
  [db run-id candidate-id stage]
  (let [hostname (tenant/hostname-of db)]
    (first (next/execute! (tenant/unwrap db)
                          (hsql/format {:select   :*
                                        :from     :curation-stage-runs
                                        :where    (cond-> [:and
                                                           [:= :run-id run-id]
                                                           [:= :candidate-id candidate-id]
                                                           [:= :stage stage]
                                                           [:= :status "ok"]]
                                                    hostname (conj [:= :hostname hostname]))
                                        :order-by [[:attempt :desc]]
                                        :limit    1})
                          {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn upsert-candidate!
  "Write or update the per-candidate head row in `curation_run_candidates`.
  Pass `:id` to re-project an existing head (e.g. as a candidate advances
  through fetch/parse/vet, or is re-projected on restart) — updates that row
  in place. Without `:id`, inserts a new head row (e.g. when a candidate is
  first discovered) and returns it, `:id` included, for the caller to thread
  into subsequent `record-stage!`/`upsert-candidate!` calls for that
  candidate. `:matches?` maps to the `matches` SQL column on both write and
  read."
  [db {:keys [id] :as candidate}]
  (let [row (->sql-row (dissoc candidate :id))]
    (->clj-row
     (if id
       (first (rdbms/scoped-update! db :curation-run-candidates {:id id} row
                                    :ex-subtype :UnableToUpdateCurationCandidate))
       (first (rdbms/insert! db :curation-run-candidates
                             (merge {:id (utils/uuid) :created-at (utils/now)} row)
                             :ex-subtype :UnableToCreateCurationCandidate))))))

(defn run-candidates
  "All head rows for a run, `:matches` read back as `:matches?`."
  [db run-id]
  (let [hostname (tenant/hostname-of db)]
    (mapv ->clj-row
          (next/execute! (tenant/unwrap db)
                         (hsql/format {:select :*
                                       :from   :curation-run-candidates
                                       :where  (cond-> [:and [:= :run-id run-id]]
                                                 hostname (conj [:= :hostname hostname]))})
                         {:builder-fn rs/as-unqualified-kebab-maps}))))

(defn record-feedback!
  "Append to `curation_feedback`. Idempotent per `(candidate-id, kind)`: if an
  `active` row already exists for that pair, toggles it to `withdrawn` and
  returns it (a re-POST withdraws); otherwise inserts a new `active` row."
  [db {:keys [interest-id run-id candidate-id kind directive]}]
  (let [hostname (tenant/hostname-of db)
        existing (first (next/execute! (tenant/unwrap db)
                                       (hsql/format {:select :*
                                                     :from   :curation-feedback
                                                     :where  (cond-> [:and
                                                                      [:= :candidate-id candidate-id]
                                                                      [:= :kind kind]
                                                                      [:= :status "active"]]
                                                               hostname (conj [:= :hostname hostname]))})
                                       {:builder-fn rs/as-unqualified-kebab-maps}))]
    (if existing
      (first (rdbms/scoped-update! db :curation-feedback {:id (:id existing)} {:status "withdrawn"}
                                   :ex-subtype :UnableToUpdateCurationFeedback))
      (first (rdbms/insert! db :curation-feedback
                            {:id           (utils/uuid)
                             :interest-id  interest-id
                             :run-id       run-id
                             :candidate-id candidate-id
                             :kind         kind
                             :directive    directive
                             :status       "active"
                             :created-at   (utils/now)}
                            :ex-subtype :UnableToCreateCurationFeedback)))))

(defn active-feedback
  "All `status = 'active'` feedback rows for the interest."
  [db interest-id]
  (let [hostname (tenant/hostname-of db)]
    (next/execute! (tenant/unwrap db)
                   (hsql/format {:select :*
                                 :from   :curation-feedback
                                 :where  (cond-> [:and
                                                  [:= :interest-id interest-id]
                                                  [:= :status "active"]]
                                           hostname (conj [:= :hostname hostname]))})
                   {:builder-fn rs/as-unqualified-kebab-maps})))
