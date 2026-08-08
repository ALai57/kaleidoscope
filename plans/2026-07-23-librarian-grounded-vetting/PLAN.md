# Librarian Grounded Vetting — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the Librarian's hallucinated-URL shelving with a grounded, auditable `discover → fetch → parse → vet → select-shelf` pipeline whose vet stage scores each candidate against a persisted, data-driven multi-dimension rubric, with a user feedback loop and full re-run/restart.

**Architecture:** Grounded discovery (Anthropic `web_search`) yields real candidates; a bounded-concurrent per-candidate `fetch → parse → vet` tail persists each stage's input+output as append-only `curation_stage_runs` rows and projects a per-candidate head (`curation_run_candidates`). The pure `select-shelf` applies the rubric's weighted aggregate + pass-line and decides the guarded shelf swap. The whole run is async (rides the workflow Postgres job queue) and every stage is resumable/re-runnable. Reader reactions (`more`/`less`/`reopen`) accumulate in an append-only `curation_feedback` stream, composed with the taste profile at run time.

**Tech Stack:** Clojure, next.jdbc + HoneySQL (`persistence/rdbms.clj`), Migratus, Malli, reitit, Anthropic Messages API (raw HTTP via `workflows/llm_executor.clj`'s `post-anthropic-sync`), Kaocha + matcher-combinators, embedded-h2 for tests.

## Global Constraints

- **3-layer separation** — no persistence calls from `http_api/`; no HTTP concerns in `api/`. Pure orchestration in `api/`, external I/O in `curation/` and `persistence/`.
- **All schema changes require a numbered migration pair** in `resources/migrations/` (`YYYYMMDDhhmmss-<slug>.up.sql` + `.down.sql`). Statement separator is `--;;`. Files must run under **both** Postgres and embedded-H2 (H2 in `MODE=PostgreSQL`). Use `uuid DEFAULT gen_random_uuid() NOT NULL`, `jsonb DEFAULT '{}'::jsonb NOT NULL`, `timestamp with time zone DEFAULT now() NOT NULL`, `character varying NOT NULL` for `hostname`.
- **Tenant scoping** is via the `TenantConn` handle passed as `db` (not an explicit `hostname` param). Every new tenant-scoped table needs a `UNIQUE (id, hostname)` constraint and a composite `FOREIGN KEY (parent_id, hostname) REFERENCES parent(id, hostname)`, and must be added to `tenant/tenant-scoped-tables` (`persistence/tenant.clj:21`) or the `tenant-scoped-tables-match-schema-test` tripwire fails.
- **JSONB is automatic** — maps/vectors bound as SQL params are encoded to JSONB and decoded back to kebab-keyworded Clojure data by the `persistence/rdbms.clj` protocol extensions. Never hand-encode.
- **Side-effecting fns end in `!`**. SQL columns are `snake_case`; Clojure is `kebab-case` (auto-converted).
- **Model id** is the existing codebase constant `claude-opus-4-6` (a real, active 1M-context model). Reuse `llm-executor/post-anthropic-sync` — do not fork a new Anthropic client.
- **Anthropic web tools** are the dynamic-filtering variants: `{"type":"web_search_20260209","name":"web_search"}` and `{"type":"web_fetch_20260209","name":"web_fetch"}`. A grounded call can stop with `stop_reason: "pause_turn"`; resume by re-sending `messages` with the assistant's `content` appended (no extra user text). Response `content` is a mixed block list — walk it filtering `:type "text"`; never assume `(-> resp :content first :text)`.
- **Every feature needs automated tests** (CLAUDE.md sharp edge #4). Pure logic → unit tests with plain maps; stages → mock-provider end-to-end under `embedded-h2/fresh-db!`.
- **Keep `docs/operations.md` and `Taskfile.yml` in sync** with any config/env or bin change (sharp edge #6).

## Prerequisite (blocks Phases 8–9)

The async lifecycle and restart phases depend on the **workflow Postgres job queue**, which is specified in `plans/2026-07-16-workflow-postgres-job-queue/PLAN.md` but **not yet implemented in `src/`** (no `persistence/workflow_jobs.clj`, no `workflow_jobs` migration, no worker). Land that plan first. Phases 1–7 and 10–12 do **not** depend on it and can proceed immediately; Phase 8 consumes its `jobs/enqueue-job!`, `jobs/claim-next-job!`, `workflows/advance-run!*`, and the `kaleidoscope.worker` loop.

Until the queue lands, `run-curation!` can retain the current in-process `(future (advance-and-shelve! …))` path (`api/curation.clj:205`) so Phases 1–7 remain end-to-end testable synchronously in tests.

---

## Phase 1 — Data model (migrations)

Creates the three new tables and additive columns. One migration pair; everything additive and drift-tolerant (prod `recommendations` has drifted — see `project_prod_schema_drift`).

### Task 1: Migration — curation tables + additive columns

**Files:**
- Create: `resources/migrations/20260726000001-add-curation-vetting.up.sql`
- Create: `resources/migrations/20260726000001-add-curation-vetting.down.sql`
- Test: `test/kaleidoscope/persistence/curation_migration_test.clj`

**Interfaces:**
- Produces tables `curation_run_candidates`, `curation_stage_runs`, `curation_feedback`; additive columns `recommendations.vetting` (jsonb, nullable), `interests.taste_profile` already jsonb (no column change — `:avoid`/`:rubric` live inside it).

- [ ] **Step 1: Write the failing test**

```clojure
(ns kaleidoscope.persistence.curation-migration-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [next.jdbc :as next]))

(deftest curation-tables-exist-after-migration
  (testing "the three curation tables and the recommendations.vetting column exist"
    (let [db (embedded-h2/fresh-db!)]
      (doseq [t ["curation_run_candidates" "curation_stage_runs" "curation_feedback"]]
        (is (some? (next/execute-one!
                     db [(format "SELECT 1 FROM information_schema.tables WHERE table_name = '%s'" t)]))
            (str t " should exist")))
      (is (some? (next/execute-one!
                   db ["SELECT 1 FROM information_schema.columns WHERE table_name = 'recommendations' AND column_name = 'vetting'"]))
          "recommendations.vetting should exist"))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test --focus kaleidoscope.persistence.curation-migration-test`
Expected: FAIL — tables do not exist.

- [ ] **Step 3: Write the up migration**

`20260726000001-add-curation-vetting.up.sql` (each statement separated by `--;;`; model the tenant idiom on `interests`/`recommendations` in `20260718000007-baseline.up.sql`):

```sql
CREATE TABLE public.curation_run_candidates (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    run_id uuid NOT NULL,
    ref jsonb DEFAULT '{}'::jsonb NOT NULL,
    kind text,
    source text,
    fetcher text,
    final_url text,
    matches boolean,
    scores jsonb,
    composite double precision,
    outcome text,
    reason text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.curation_run_candidates
    ADD CONSTRAINT curation_run_candidates_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.curation_run_candidates
    ADD CONSTRAINT curation_run_candidates_id_hostname_unique UNIQUE (id, hostname);
--;;
CREATE INDEX idx_curation_run_candidates_run_id ON public.curation_run_candidates USING btree (run_id);
--;;
CREATE TABLE public.curation_stage_runs (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    run_id uuid NOT NULL,
    candidate_id uuid,
    stage text NOT NULL,
    attempt integer DEFAULT 1 NOT NULL,
    status text DEFAULT 'pending'::text NOT NULL,
    input jsonb,
    output jsonb,
    provenance jsonb DEFAULT '{}'::jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.curation_stage_runs
    ADD CONSTRAINT curation_stage_runs_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.curation_stage_runs
    ADD CONSTRAINT curation_stage_runs_id_hostname_unique UNIQUE (id, hostname);
--;;
CREATE INDEX idx_curation_stage_runs_run_candidate ON public.curation_stage_runs USING btree (run_id, candidate_id, stage);
--;;
CREATE TABLE public.curation_feedback (
    id uuid DEFAULT gen_random_uuid() NOT NULL,
    interest_id uuid NOT NULL,
    run_id uuid,
    candidate_id uuid,
    kind text NOT NULL,
    directive text,
    status text DEFAULT 'active'::text NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    hostname character varying NOT NULL
);
--;;
ALTER TABLE public.curation_feedback
    ADD CONSTRAINT curation_feedback_pkey PRIMARY KEY (id);
--;;
ALTER TABLE public.curation_feedback
    ADD CONSTRAINT curation_feedback_id_hostname_unique UNIQUE (id, hostname);
--;;
CREATE INDEX idx_curation_feedback_interest_id ON public.curation_feedback USING btree (interest_id);
--;;
ALTER TABLE public.recommendations ADD COLUMN IF NOT EXISTS vetting jsonb;
```

> Note: `matches` (not `matches?`) — SQL columns can't hold `?`; the Clojure key `:matches?` maps to it explicitly in persistence (Task 20). FK to `interests`/`project_workflow_runs` is intentionally omitted to stay drift-tolerant across the prod schema; tenant integrity is still enforced by the `(id, hostname)` uniqueness + hostname scoping.

- [ ] **Step 4: Write the down migration**

`20260726000001-add-curation-vetting.down.sql`:

```sql
DROP TABLE IF EXISTS public.curation_feedback;
--;;
DROP TABLE IF EXISTS public.curation_stage_runs;
--;;
DROP TABLE IF EXISTS public.curation_run_candidates;
--;;
ALTER TABLE public.recommendations DROP COLUMN IF EXISTS vetting;
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./bin/test --focus kaleidoscope.persistence.curation-migration-test`
Expected: PASS.

- [ ] **Step 6: Add the new tables to the tenant-scoped set**

Edit `persistence/tenant.clj` — add `"curation_run_candidates"`, `"curation_stage_runs"`, `"curation_feedback"` to `tenant-scoped-tables` (`tenant.clj:21`). Update the `project_tenant_scoped_tables` memory afterward.

- [ ] **Step 7: Run the tenant tripwire + commit**

Run: `./bin/test --focus kaleidoscope.persistence.tenant-test`
Expected: PASS (`tenant-scoped-tables-match-schema-test` green).

```bash
git add resources/migrations/20260726000001-add-curation-vetting.{up,down}.sql \
        src/kaleidoscope/persistence/tenant.clj \
        test/kaleidoscope/persistence/curation_migration_test.clj
git commit -m "feat(curation): add curation vetting tables + recommendations.vetting"
```

---

## Phase 2 — Data contracts + rubric + pure combine (no I/O)

The stable Malli contract and the pure scoring math. Zero mocks, zero DB.

### Task 2: Malli schemas for candidate / fetched / content / verdict / rubric

**Files:**
- Create: `src/kaleidoscope/models/curation.clj`
- Test: `test/kaleidoscope/models/curation_test.clj`

**Interfaces:**
- Produces `Candidate`, `Fetched`, `Content`, `Verdict`, `Rubric`, `Dimension` Malli schemas + `default-rubric` presets map `{"quick" .. "standard" .. "rigorous" ..}`.

- [ ] **Step 1: Write the failing test**

```clojure
(ns kaleidoscope.models.curation-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.models.curation :as m]
            [malli.core :as malli]))

(deftest verdict-schema-test
  (testing "verdict carries identity gate + open dimension scores + composite"
    (is (malli/validate m/Verdict
          {:matches? true
           :scores {:relevance 8 :depth 7 :taste-fit 9 :novelty 4}
           :composite 7.4 :why {} :est-time "18 min" :reason "on-taste"}))
    (is (not (malli/validate m/Verdict {:matches? true})) "scores + composite required")))

(deftest rubric-schema-and-presets-test
  (testing "a rubric is dimensions + pass-line + aggregate; presets validate"
    (is (malli/validate m/Rubric (get m/default-rubric "standard")))
    (is (= :weighted-mean (:aggregate (get m/default-rubric "standard"))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.models.curation-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Write the schemas**

```clojure
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.models.curation-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/models/curation.clj test/kaleidoscope/models/curation_test.clj
git commit -m "feat(curation): Malli contracts for pipeline data + scoring rubric"
```

### Task 3: `aggregate` + `select-shelf` (pure combine, pass-line, disposition)

**Files:**
- Create: `src/kaleidoscope/api/curation/vetting.clj`
- Test: `test/kaleidoscope/api/curation/vetting_test.clj`

**Interfaces:**
- Consumes `models.curation` schemas.
- Produces `(aggregate scores rubric) -> composite` (weighted mean over rubric dimensions, missing score = 0), and `(select-shelf pool rubric config) -> {:shelved [candidate] :dropped [candidate] :disposition :swapped|:kept-prior}`. Each pool entry is `{:candidate :fetched :verdict}` where `:verdict` may be `nil` for `:dead` (fetch failed). Downstream Task 21 (`vet-candidates`) produces this pool shape.

- [ ] **Step 1: Write the failing test**

```clojure
(ns kaleidoscope.api.curation.vetting-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.vetting :as vet]
            [kaleidoscope.models.curation :as m]))

(def rubric (get m/default-rubric "standard"))

(deftest aggregate-is-weighted-mean
  (testing "composite = Σ(w·s)/Σw over rubric dimensions"
    ;; weights 1,1,1,0.5 ; scores 8,7,9,4 -> (8+7+9+2)/3.5 = 26/3.5 = 7.428...
    (is (< 7.42 (vet/aggregate {:relevance 8 :depth 7 :taste-fit 9 :novelty 4} rubric) 7.43))
    (testing "a missing dimension score counts as 0"
      (is (= 0.0 (vet/aggregate {} rubric))))))

(deftest select-shelf-drops-and-disposition
  (let [ok    {:candidate {:title "A" :origin "trusted"}
               :verdict {:matches? true :scores {:relevance 8 :depth 8 :taste-fit 8 :novelty 6} :composite 7.7}}
        wrong {:candidate {:title "B"} :verdict {:matches? false :composite 9.0 :scores {}}}
        thin  {:candidate {:title "C"} :verdict {:matches? true :composite 4.0 :scores {}}}
        dead  {:candidate {:title "D"} :fetched {:status :dead} :verdict nil}
        cfg   {:shelf-size 6 :min-viable 3}]
    (testing "identity fail -> off-taste, below pass-line -> below-threshold, dead -> dead"
      (let [{:keys [shelved dropped disposition]} (vet/select-shelf [ok wrong thin dead] rubric cfg)]
        (is (= #{"A"} (set (map :title shelved))))
        (is (= "off-taste"       (:outcome (first (filter #(= "B" (:title %)) dropped)))))
        (is (= "below-threshold" (:outcome (first (filter #(= "C" (:title %)) dropped)))))
        (is (= "dead"            (:outcome (first (filter #(= "D" (:title %)) dropped)))))
        (is (= :kept-prior disposition) "1 survivor < min-viable 3 -> kept-prior")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.curation.vetting-test`
Expected: FAIL — namespace not found.

- [ ] **Step 3: Write `aggregate` + `select-shelf`**

```clojure
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
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.curation.vetting-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/curation/vetting.clj test/kaleidoscope/api/curation/vetting_test.clj
git commit -m "feat(curation): pure aggregate + select-shelf (rubric combine, pass-line, guard)"
```

---

## Phase 3 — Fetch registry + policy + providers

`fetch` tries providers in config order; first `:ok` wins; failures are data. Providers do I/O.

### Task 4: `fetch` policy over a registry (pure fallthrough)

**Files:**
- Create: `src/kaleidoscope/api/curation/providers.clj`
- Test: `test/kaleidoscope/api/curation/providers_test.clj`

**Interfaces:**
- Produces `fetchers` (atom-free `def` map keyword→fn), `(fetch ref config) -> Fetched`. Providers implemented in later tasks are registered by assoc.

- [ ] **Step 1: Write the failing test** (stub the registry via `with-redefs`):

```clojure
(ns kaleidoscope.api.curation.providers-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.providers :as p]))

(deftest fetch-policy-fallthrough
  (testing "tries policy order, first :ok wins, all-fail -> :dead"
    (with-redefs [p/fetchers {:a (fn [_] {:status :error})
                              :b (fn [_] {:status :ok :final-url "u" :fetcher :b})
                              :c (fn [_] {:status :ok :fetcher :c})}]
      (is (= :b (:fetcher (p/fetch {:url "x"} {:fetch-policy [:a :b :c]}))))
      (is (= :dead (:status (p/fetch {:url "x"} {:fetch-policy [:a]})))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.curation.providers-test`
Expected: FAIL.

- [ ] **Step 3: Write `fetch` + registry skeleton**

```clojure
(ns kaleidoscope.api.curation.providers
  (:require [kaleidoscope.api.curation.http :as http]
            [kaleidoscope.api.curation.anthropic-web :as anthropic-web]
            [kaleidoscope.api.curation.firecrawl :as firecrawl]
            [kaleidoscope.api.curation.mock :as mock]))

(def fetchers
  {:http      #'http/fetch
   :anthropic #'anthropic-web/fetch
   :firecrawl #'firecrawl/fetch
   :mock      #'mock/fetch})

(defn fetch
  "Try each fetcher named in :fetch-policy in order; first :ok wins. Failure is data."
  [ref {:keys [fetch-policy] :as _config}]
  (or (->> fetch-policy
           (map (fn [k] ((get fetchers k) ref)))
           (some (fn [{:keys [status] :as r}] (when (= :ok status) r))))
      {:status :dead :ref ref}))
```

> The `:require`d provider namespaces are created in Tasks 5–7; create empty stubs (each `(ns …)` with a `(defn fetch [_ref] {:status :error})`) so this compiles, then fill them in.

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.curation.providers-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/curation/providers.clj \
        src/kaleidoscope/api/curation/{http,anthropic_web,firecrawl,mock}.clj \
        test/kaleidoscope/api/curation/providers_test.clj
git commit -m "feat(curation): fetch registry + explicit-policy fallthrough"
```

### Task 5: `mock` provider (discover/fetch/vet canned data, offline)

**Files:**
- Modify: `src/kaleidoscope/api/curation/mock.clj`
- Test: `test/kaleidoscope/api/curation/mock_test.clj`

**Interfaces:**
- Produces `(mock/fetch ref)`, `(mock/discover taste config)`, `(mock/vet content taste rubric)` returning deterministic contract-shaped data, including a live-but-wrong-target candidate and an off-taste candidate for regression coverage. Mirror `workflows/mock.clj`'s `mock-candidates` style.

- [ ] **Step 1: Write the failing test**

```clojure
(ns kaleidoscope.api.curation.mock-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.mock :as mock]
            [kaleidoscope.models.curation :as m]
            [malli.core :as malli]))

(deftest mock-shapes-validate
  (testing "fetch/vet return contract-shaped data"
    (is (malli/validate m/Fetched (mock/fetch {:url "https://example.com/ok/1"})))
    (is (= :dead (:status (mock/fetch {:url "https://example.com/dead/1"}))))
    (is (malli/validate m/Verdict
          (mock/vet {:title "A" :text "..."} {} (get m/default-rubric "standard"))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.curation.mock-test`
Expected: FAIL.

- [ ] **Step 3: Implement the mock provider**

```clojure
(ns kaleidoscope.api.curation.mock
  (:require [clojure.string :as str]))

(defn fetch [{:keys [url] :as ref}]
  (cond
    (str/includes? (or url "") "/dead/")  {:status :dead :ref ref}
    :else {:status :ok :final-url url :raw "<html>mock</html>"
           :content-type "text/html" :fetcher :mock}))

(defn vet [content _taste rubric]
  ;; deterministic: title starting "WRONG" fails identity; "NOISE" scores low
  (let [t (:title content "")
        base (cond (str/starts-with? t "WRONG") {:matches? false :scores {} :composite 0.0 :reason "different resource"}
                   (str/starts-with? t "NOISE") {:matches? true :scores {:relevance 2 :depth 2 :taste-fit 2 :novelty 2} :reason "tangential"}
                   :else {:matches? true :scores {:relevance 8 :depth 8 :taste-fit 8 :novelty 6}})]
    (assoc base :composite (or (:composite base) 0.0) :est-time "15 min")))

(defn discover [taste _config]
  ;; three good, one wrong-target, one noise
  [{:kind "article" :title "Deep Work primer" :source "calnewport.com" :ref {:url "https://example.com/ok/1"} :why "on-taste"}
   {:kind "book"    :title "Solitude"        :source "standardebooks.org" :ref {:url "https://example.com/ok/2"} :why "on-taste"}
   {:kind "paper"   :title "Attention"       :source "fs.blog" :ref {:url "https://example.com/ok/3"} :why "on-taste"}
   {:kind "article" :title "WRONG target"    :source "example.com" :ref {:url "https://example.com/ok/4"} :why "looks right"}
   {:kind "article" :title "NOISE roundup"   :source "noise.example" :ref {:url "https://example.com/ok/5"} :why "listicle"}])
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.curation.mock-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/curation/mock.clj test/kaleidoscope/api/curation/mock_test.clj
git commit -m "feat(curation): mock discover/fetch/vet for offline tests"
```

### Task 6: `http` fetch provider (homespun java.net.http)

**Files:**
- Modify: `src/kaleidoscope/api/curation/http.clj`
- Test: `test/kaleidoscope/api/curation/http_test.clj`

**Interfaces:**
- Produces `(http/fetch ref) -> Fetched`. Uses the same `java.net.http.HttpClient` pattern as `llm_executor.clj:46`; follows redirects (record `:final-url`), maps 2xx→`:ok`, dead hosts/4xx/5xx→`:dead`, exceptions→`{:status :error}`.

- [ ] **Step 1: Write the failing test** — assert error/dead are data, not thrown:

```clojure
(ns kaleidoscope.api.curation.http-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.http :as http]))

(deftest http-failures-are-data
  (testing "an unresolvable host returns {:status :error} not an exception"
    (is (contains? #{:error :dead} (:status (http/fetch {:url "https://nonexistent.invalid/x"}))))))
```

- [ ] **Step 2: Run to verify it fails** — Run: `./bin/test --focus kaleidoscope.api.curation.http-test` → FAIL (stub returns `:error` already? replace stub with a real GET that still returns data on failure; keep the assertion set `#{:error :dead}` so the stub also passes — then Step 3 makes it a real fetch).

- [ ] **Step 3: Implement `http/fetch`**

```clojure
(ns kaleidoscope.api.curation.http
  (:import [java.net URI]
           [java.net.http HttpClient HttpClient$Redirect HttpRequest HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private client
  (delay (-> (HttpClient/newBuilder)
             (.connectTimeout (Duration/ofSeconds 10))
             (.followRedirects HttpClient$Redirect/NORMAL)
             (.build))))

(defn fetch [{:keys [url] :as ref}]
  (try
    (let [req  (-> (HttpRequest/newBuilder (URI/create url))
                   (.timeout (Duration/ofSeconds 20)) (.GET) (.build))
          resp (.send @client req (HttpResponse$BodyHandlers/ofString))
          code (.statusCode resp)]
      (if (<= 200 code 299)
        {:status :ok :final-url (str (.uri resp)) :raw (.body resp)
         :content-type (-> resp .headers (.firstValue "content-type") (.orElse "")) :fetcher :http}
        {:status :dead :ref ref :http-status code}))
    (catch Exception e {:status :error :ref ref :error (.getMessage e)})))
```

- [ ] **Step 4: Run to verify it passes** — Run: `./bin/test --focus kaleidoscope.api.curation.http-test` → PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/curation/http.clj test/kaleidoscope/api/curation/http_test.clj
git commit -m "feat(curation): homespun http fetch provider (failures as data)"
```

### Task 7: `anthropic-web` + `firecrawl` fetch providers

**Files:**
- Modify: `src/kaleidoscope/api/curation/anthropic_web.clj`, `src/kaleidoscope/api/curation/firecrawl.clj`
- Test: `test/kaleidoscope/api/curation/anthropic_web_test.clj`

**Interfaces:**
- `(anthropic-web/fetch ref)` calls `llm-executor/post-anthropic-sync` with a `web_fetch_20260209` tool over the candidate URL, walks `:content` for the fetched `document`, returns `Fetched`. `(firecrawl/fetch ref)` reuses the existing `api/firecrawl.clj` client. Both return `{:status :error}` on failure (never throw).

- [ ] **Step 1: Write the failing test** (mock `post-anthropic-sync`):

```clojure
(ns kaleidoscope.api.curation.anthropic-web-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.anthropic-web :as aw]
            [kaleidoscope.workflows.llm-executor :as llm]))

(deftest anthropic-web-fetch-extracts-document
  (testing "walks the mixed content list for the web_fetch document"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_k _body]
                    {:content [{:type "web_fetch_tool_result"
                                :content {:type "web_fetch_result"
                                          :url "https://ex/final"
                                          :content {:type "document" :source {:data "PAGE TEXT"}}}}
                               {:type "text" :text "done"}]})]
      (let [{:keys [status raw final-url]} (aw/fetch {:url "https://ex/x"} {:api-key "k"})]
        (is (= :ok status))
        (is (= "https://ex/final" final-url))
        (is (re-find #"PAGE TEXT" (str raw)))))))
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement** `anthropic-web/fetch` (2-arity `[ref config]`; wrap the registry call in `providers.clj` so it threads config), extracting the `web_fetch_tool_result` block; and `firecrawl/fetch` delegating to `api/firecrawl.clj`. Handle `pause_turn` by re-sending with the assistant content appended (cap re-sends at 3). Return `{:status :error}` on any exception or missing document.

> Registry note: `fetchers` values take a single `ref`, but anthropic/firecrawl need `config` (api-key). In `providers.clj`, close over config when building the effective policy: change `fetch` to `((get fetchers k) ref config)` and give `http/fetch`/`mock/fetch` an ignored 2nd arg, OR keep 1-arity and inject api-key via a dynamic var set in `vet-candidates`. Choose the explicit 2-arity form and update Task 4's `fetchers` fns to `[ref config]`. Update Task 4/5/6 signatures accordingly in this task and re-run their tests.

- [ ] **Step 4: Run to verify it passes** — `./bin/test --focus kaleidoscope.api.curation.anthropic-web-test` → PASS; re-run providers/http/mock tests to confirm the 2-arity change is consistent.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/curation/{anthropic_web,firecrawl,providers,http,mock}.clj \
        test/kaleidoscope/api/curation/anthropic_web_test.clj
git commit -m "feat(curation): anthropic web_fetch + firecrawl fetch providers"
```

---

## Phase 4 — Parse (host-dispatch multimethod)

### Task 8: `parse` multimethod (youtube / goodreads / readability default)

**Files:**
- Modify: `src/kaleidoscope/api/curation/providers.clj` (add `parse` multimethod + `host-of`)
- Create: `src/kaleidoscope/api/curation/parse/{youtube,goodreads,readability}.clj`
- Test: `test/kaleidoscope/api/curation/parse_test.clj`

**Interfaces:**
- Produces `(parse fetched) -> Content` dispatching on `(host-of (:final-url fetched))`. `youtube.com` → oEmbed availability; `goodreads.com` → title/author; `:default` → readability/html→text. Each sets `:available?` where knowable.

- [ ] **Step 1: Write the failing test**

```clojure
(ns kaleidoscope.api.curation.parse-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.providers :as p]))

(deftest parse-dispatches-on-host
  (testing "default readability extracts text + title from html"
    (let [c (p/parse {:final-url "https://blog.example/x"
                      :raw "<html><head><title>Deep Work</title></head><body><p>Focus.</p></body></html>"
                      :content-type "text/html"})]
      (is (= "Deep Work" (:title c)))
      (is (re-find #"Focus" (:text c))))))
```

- [ ] **Step 2: Run to verify it fails** — FAIL.

- [ ] **Step 3: Implement** `host-of`, the `parse` multimethod, and the three methods. Keep readability minimal (strip tags, pull `<title>`); youtube/goodreads may start as thin metadata extractors from the HTML with a `TODO`-free simple heuristic (title/author regex) — do not leave placeholders; implement a real basic extraction.

- [ ] **Step 4: Run to verify it passes** — PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/curation/providers.clj \
        src/kaleidoscope/api/curation/parse/*.clj \
        test/kaleidoscope/api/curation/parse_test.clj
git commit -m "feat(curation): parse multimethod (youtube/goodreads/readability)"
```

---

## Phase 5 — Grounded discover

### Task 9: `discover` — grounded Anthropic `web_search`

**Files:**
- Create: `src/kaleidoscope/api/curation/discover.clj`
- Modify: `src/kaleidoscope/scoring/agents.clj` (discover prompt asks for real, live URLs; renders `:avoid` + composed rubric-aware directives)
- Test: `test/kaleidoscope/api/curation/discover_test.clj`

**Interfaces:**
- Produces `(discover taste config) -> [Candidate]`. Builds a `web_search_20260209` request from `agents/format-taste-profile-context` (extended to render `:avoid` and active `more`/`less` directives), parses the returned candidate list, and **enforces grounding**: every `:ref/:url` must appear in a `web_search_tool_result`; invented URLs are dropped. Over-discovers `ceil(shelf-size × over-discover-factor)`.

- [ ] **Step 1: Write the failing test** (mock `post-anthropic-sync` to return search results + a candidate JSON, plus one invented URL that must be dropped).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `discover`, the grounding filter (collect all URLs from `web_search_tool_result` blocks; keep only candidates whose url ∈ that set), and the `pause_turn` resume loop. Extend `agents/format-taste-profile-context` to render `:avoid` and a `<directives>` block from composed feedback (Task 24 supplies the composed input).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): grounded web_search discover with URL-grounding filter`.

---

## Phase 6 — Vet (LLM scoring against the rubric)

### Task 10: `vet` — one LLM call emitting per-dimension scores + identity

**Files:**
- Create: `src/kaleidoscope/api/curation/vet.clj`
- Modify: `src/kaleidoscope/scoring/agents.clj` (vet system prompt)
- Test: `test/kaleidoscope/api/curation/vet_test.clj`

**Interfaces:**
- Produces `(vet content taste rubric config) -> Verdict`. The prompt is **generated from `(:dimensions rubric)`** — each dimension's `:description` becomes a scored axis; the model returns `{:matches? :scores {<dim-id> 0-10 …} :why :reason :est-time}`. `vet` does **not** compute `:composite` (that's `select-shelf`), but may echo it as `nil`. Uses `output_config.format`-style JSON contract via the existing `extract-json` helper.

- [ ] **Step 1: Write the failing test** (mock `post-anthropic-sync` returning a fenced-JSON verdict with the 4 dimension scores; assert `vet` returns a `Verdict` whose `:scores` keys match the rubric dimension ids).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `vet` + prompt generation from `:dimensions`. Assert (Malli) the parsed verdict; on parse failure return `{:matches? false :scores {} :composite 0.0 :reason "unparseable vet output"}` (a malformed vet drops the candidate, never throws).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): rubric-driven vet LLM scoring`.

---

## Phase 7 — Persistence + orchestration (`vet-candidates`, `shelve!` rewrite)

### Task 20: `persistence/curation.clj` — stage-runs, head, feedback

**Files:**
- Create: `src/kaleidoscope/persistence/curation.clj`
- Test: `test/kaleidoscope/persistence/curation_test.clj`

**Interfaces:**
- Produces:
  - `(record-stage! db {:run-id :candidate-id :stage :attempt :status :input :output :provenance}) -> row`
  - `(latest-ok-stage db run-id candidate-id stage) -> row|nil`
  - `(upsert-candidate! db {:run-id :ref :kind :source :fetcher :final-url :matches? :scores :composite :outcome :reason}) -> row` (writes `:matches?` to the `matches` column)
  - `(run-candidates db run-id) -> [row]`
  - `(record-feedback! db {:interest-id :run-id :candidate-id :kind :directive}) -> row` (idempotent per `(candidate,kind)` — re-POST toggles `active`↔`withdrawn`)
  - `(active-feedback db interest-id) -> [row]`
- Uses `rdbms/insert!` / `rdbms/scoped-update!` / hand-written HoneySQL like `persistence/recommendations.clj`. All tenant-scoped via the `db` handle.

- [ ] **Step 1: Write the failing test** under `embedded-h2/fresh-db!`: record two `vet` attempts for a candidate, assert `latest-ok-stage` returns attempt 2; upsert a candidate and read it back with `:matches?` round-tripping; record feedback twice and assert the toggle to `withdrawn`.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the read/write fns. Map `:matches?`↔`matches` explicitly (rename-keys on write and read). `scores`/`input`/`output`/`provenance`/`ref`/`vetting` are plain maps → JSONB automatically.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): persistence for stage-runs, candidate head, feedback stream`.

### Task 21: `vet-candidates` — bounded-concurrent fetch→parse→vet, persist each stage

**Files:**
- Modify: `src/kaleidoscope/api/curation/vetting.clj`
- Test: `test/kaleidoscope/api/curation/vet_candidates_test.clj`

**Interfaces:**
- Consumes `providers/fetch`, `providers/parse`, `vet/vet`, `persistence/curation`.
- Produces `(vet-candidates db run-id candidates rubric config) -> pool` where `pool` is the `[{:candidate :fetched :content :verdict}]` shape `select-shelf` consumes. EFFECTS: bounded-concurrent (`:vet-concurrency`, default 6) per-candidate `fetch → parse → vet`; persists each stage's input+output as an append-only `curation_stage_runs` row (status `ok`/`error`) with provenance including `:fetcher`, `:model-id`, `:prompt-version`, `:taste-profile-snapshot`, `:rubric-snapshot`, timings; projects the head incrementally via `upsert-candidate!`.

- [ ] **Step 1: Write the failing test** with the mock providers: mock discover yields 5 candidates (one `/dead/`, one `WRONG`, one `NOISE`); assert exactly the 3 good survive `select-shelf`, a stage-run row exists per (candidate,stage), and each dropped candidate's head row has an `:outcome` + `:reason`. This is the **vetting funnel end-to-end** test and the **original-bug regression** (WRONG target with HTTP 200 is dropped `off-taste`, not shelved).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** `vet-candidates` using a bounded thread pool (`claypoole` if already a dep, else `(pmap)` capped by partitioning into `:vet-concurrency`-sized chunks). Persist provenance with the `:rubric-snapshot` from `config`. Content-address large `:raw` blobs to the object store only if `plans/2026-07-18-object-storage-model` primitives are wired; otherwise inline small structured `output` and store `raw` as a `{:sha256 :size}` stub (leave the blob write behind a `store-raw!` fn with a `:mock` no-op default so this task doesn't block on object storage).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): vet-candidates orchestration with per-stage persistence`.

### Task 22: Rewrite `shelve!` tail to use vetting + select-shelf

**Files:**
- Modify: `src/kaleidoscope/api/curation.clj`
- Test: `test/kaleidoscope/api/curation_test.clj` (extend existing)

**Interfaces:**
- Consumes `vet-candidates`, `select-shelf`, the rubric (from `interests.taste_profile.:rubric` or the seed preset), and the existing `tag-origin`/`novelty-quota`/`split-candidates` primitives (kept). `shelve!` now: `discover` (grounded, via the executor) → `vet-candidates` → `select-shelf` → guarded archive+create (min-viable widened from `seq` to `< min-viable`) → write `recommendations.vetting` (composite + scores) → mark run.
- Removes `relevance-configs`/`drop-below-threshold` usage from the tail (keep the fns until Phase 12 config removes them, to avoid a wide diff).

- [ ] **Step 1: Extend the failing test** in `api/curation_test.clj`: an all-dead discovery leaves the prior shelf intact (`kept-prior`); a healthy discovery swaps and writes `vetting` on each recommendation.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Rewrite** `shelve!` (replace the `threshold`/`drop-below-threshold` body per DESIGN.md §Pure/effectful split). Resolve the rubric: `(or (get-in interest [:taste-profile :rubric]) (get m/default-rubric scrutiny))`. Widen the min-viable guard in `recommendations-persistence/create-recommendations!` call site (guard on `>= min-viable`).
- [ ] **Step 4: Run → PASS** (`./bin/test --focus kaleidoscope.api.curation-test`).
- [ ] **Step 5: Commit** `feat(curation): grounded fetch→parse→vet→select-shelf tail replaces threshold shelving`.

---

## Phase 8 — Async lifecycle (**requires the workflow job queue**)

### Task 30: enqueue + worker + report endpoint

**Files:**
- Modify: `src/kaleidoscope/api/curation.clj` (`run-curation!` enqueues instead of `future`)
- Create: `src/kaleidoscope/http_api/curation.clj` (`POST …/curate`, `GET …/curation/runs/:id`)
- Modify: `src/kaleidoscope/http_api/interests.clj` (mount the new routes or move curate here)
- Test: `test/kaleidoscope/http_api/curation_test.clj`

**Interfaces:**
- Consumes `jobs/enqueue-job!`, `workflows/advance-run!*` (from the job-queue plan).
- Produces `POST …/interests/:id/curate` → `{run-id, status "queued"}` immediately; the worker runs `Refine → Discover(grounded) → vet-candidates → select-shelf → guarded swap → mark run`; `GET …/curation/runs/:run-id` → `{:status :funnel :candidates}` where `:funnel` is `frequencies` over `curation_run_candidates.outcome` at read time (`{:discovered :fetched :dead :off-taste :below-threshold :shelved :shortfall :fetch-mix :latency-ms :disposition}`) and `:candidates` is the head projection with per-candidate `:scores`/`:composite`/`:matches?`.

- [ ] **Step 1: Write the failing e2e test** (route-level `test-app` pattern from `interests_test.clj:21`, mock executor): POST curate → 200 with a run-id → worker drains (call `work-once!` in the test) → GET report reflects the funnel and per-candidate matrix data.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the enqueue path, the report read model (funnel derived, not stored), and route wiring. Keep `awaiting_input` (clarify) surfacing intact.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): async run lifecycle + derived-funnel report endpoint`.

---

## Phase 9 — Auditability & restart (**requires the job queue**)

### Task 40: `POST …/curation/runs/:id/restart {from-stage?}`

**Files:**
- Modify: `src/kaleidoscope/api/curation.clj`, `src/kaleidoscope/http_api/curation.clj`, `src/kaleidoscope/persistence/curation.clj`
- Test: `test/kaleidoscope/http_api/curation_restart_test.clj`

**Interfaces:**
- Produces the restart endpoint with two modes: **resume** (no `from-stage`) — re-enqueue; stages already `ok` are skipped and their outputs reused (no re-fetch of what succeeded); **re-run-from-stage** (`from-stage ∈ discover|fetch|parse|vet`) — append a new `attempt` (never overwrite), reuse persisted upstream artifacts by hash, re-execute forward, re-project the head + re-run the guarded swap. Idempotency via the job idempotency key.

- [ ] **Step 1: Write the failing tests** — (a) **resume**: kill mid-vet (mock fetch throws on candidate 4) → restart → candidates 1–3 not re-fetched (assert no new `fetch` stage-runs for them). (b) **re-run from vet**: completed run, `restart {from-stage vet}` with a swapped mock vet → new `vet` attempts appended, `fetch`/`parse` rows untouched, head + shelf re-project. (c) **idempotency**: a re-delivered stage job for an already-`ok` attempt is a no-op.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** attempt bookkeeping, upstream-artifact reuse, downstream invalidation-as-data, and terminal-attempt projection.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): resumable + re-run-from-stage restart`.

---

## Phase 10 — Feedback signals

### Task 50: reactions endpoint + compose-at-runtime + reopen

**Files:**
- Modify: `src/kaleidoscope/persistence/curation.clj` (feedback already in Task 20), `src/kaleidoscope/api/curation.clj`, `src/kaleidoscope/http_api/curation.clj`, `src/kaleidoscope/scoring/agents.clj`
- Test: `test/kaleidoscope/http_api/curation_feedback_test.clj`

**Interfaces:**
- Produces `POST …/curation/runs/:id/candidates/:cid/feedback {kind, directive?}` (idempotent per `(candidate,user)`; re-POST/DELETE toggles withdraw) and a compose function `(composed-taste db interest) -> taste-profile-plus-directives` that folds `active` `more`/`less` into the `<taste_profile>`/`<directives>` blocks discover **and** vet read. `reopen` on a dropped candidate immediately shelves it (user-pinned, `reopened`, bypassing the vet gate + min-viable) and records a vet-calibration signal. `interests.taste_profile` gains `:avoid` (additive, defaulted).

- [ ] **Step 1: Write the failing tests** — **compose** (`more`+`less` on run N appear in run N+1's discover `<taste_profile>` **and** vet input), **withdraw** (re-click removes it; no residue), **reopen** (dropped candidate shelved immediately, no re-run, calibration signal recorded), **promote** (promoting a reaction moves its directive into `:refinements`/`:avoid` and marks the event `promoted`).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the endpoint, `composed-taste`, reopen immediate-shelf write, and promote. Discover/vet must take the **composed** input (Task 9/10 read it).
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): per-candidate feedback stream composed at run time + reopen override`.

---

## Phase 11 — Scoring UI read model (matrix, Proj preview, promote)

### Task 60: rubric persistence + `rejudge/preview` (pure) + `rubric/promote`

**Files:**
- Modify: `src/kaleidoscope/persistence/interests.clj` (read/write `taste_profile.:rubric`), `src/kaleidoscope/api/curation.clj`, `src/kaleidoscope/http_api/curation.clj`
- Test: `test/kaleidoscope/http_api/curation_rubric_test.clj`

**Interfaces:**
- Produces:
  - Report already exposes per-candidate `:scores`/`:composite`/`:matches?` (Task 30) — the matrix columns are generated from the run's `provenance.:rubric-snapshot`.
  - `POST …/curation/runs/:id/rejudge/preview {rubric}` → **pure** recompute (`select-shelf` over persisted `:scores` under the edited rubric) returning per-candidate `Proj` pass/drop + composite; **no LLM, no writes**.
  - `POST …/interests/:id/rubric/promote {rubric}` → copies the candidate rubric onto `interests.taste_profile.:rubric`.

- [ ] **Step 1: Write the failing tests** — **Proj pure recompute** (given a run's persisted `:scores`, re-weighting changes the pass/drop set with no new stage-runs and matches `rejudge/preview`), **promote lifecycle** (candidate rubric tried on a run leaves the default untouched until promote; run provenance records the rubric used).
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the stateless preview handler (reuses `vetting/select-shelf`) and the promote write.
- [ ] **Step 4: Run → PASS.**
- [ ] **Step 5: Commit** `feat(curation): pure Proj rejudge preview + rubric promote`.

---

## Phase 12 — Config, observability, docs

### Task 70: config knobs + init.env wiring

**Files:**
- Modify: `src/kaleidoscope/init/env.clj`, `env.local.example`, `docs/operations.md`, `Taskfile.yml` (only if a bin/task changes)
- Test: `test/kaleidoscope/init/env_test.clj` (extend)

**Interfaces:**
- Produces config: `KALEIDOSCOPE_CURATION_FETCH_POLICY` (default `anthropic,http`), `KALEIDOSCOPE_CURATION_VET_CONCURRENCY` (6), `KALEIDOSCOPE_CURATION_OVER_DISCOVER_FACTOR` (2.5), `KALEIDOSCOPE_CURATION_MIN_VIABLE_SHELF` (3), `KALEIDOSCOPE_CURATION_RAW_BLOB_RETENTION_DAYS` (30), `KALEIDOSCOPE_CURATION_DEFAULT_RUBRIC` (`quick|standard|rigorous` seed preset). The old single relevance-threshold knob is subsumed; delete `relevance-configs` now that the tail no longer reads it.
- Wire per `env.clj:276` boot-instruction pattern; thread config into `run-curation!`.

- [ ] **Step 1: Write the failing test** — a booted system exposes the curation config with defaults; an override changes `:fetch-policy`.
- [ ] **Step 2: Run → FAIL.**
- [ ] **Step 3: Implement** the env reads + defaults; update `env.local.example` and `docs/operations.md` (new env vars, the worker process if the job-queue plan added one). Remove `relevance-configs`/`relevance-config`/`drop-below-threshold`.
- [ ] **Step 4: Run → PASS** + full suite `task test:summary`.
- [ ] **Step 5: Commit** `feat(curation): config knobs + init.env wiring; retire relevance-threshold`.

### Task 71: observability (OTEL spans, funnel logs, counters)

**Files:**
- Modify: `src/kaleidoscope/api/curation/vetting.clj`, `src/kaleidoscope/api/curation.clj`
- Test: covered by span-presence assertions or manual (spans are hard to unit-test; assert the enriched `shelve!` log map shape).

**Interfaces:**
- Produces OTEL spans per stage nested under `kaleidoscope.curation.run` (`…curation.fetch` attrs `fetcher`/`host`/`status`/`final-url`; `…curation.vet` attrs `composite`/`matches?` + one per dimension score), the enriched Timbre `shelve!` funnel line, and counters (dead-link-rate per fetcher, off-taste-rate per host, reopen-rate per host).

- [ ] **Step 1: Write a failing test** asserting the funnel summary map returned by `shelve!` includes `:below-threshold`/`:off-taste`/`:dead` counts and `:disposition`.
- [ ] **Step 2–4:** implement `span/with-span!` wrapping + the enriched summary; run → PASS.
- [ ] **Step 5: Commit** `feat(curation): OTEL spans, funnel logging, calibration counters`.

---

## Self-review notes (for the implementer)

- **Task 7 changes the `fetchers` fn arity** (`[ref config]`). When you reach it, update Tasks 4/5/6 signatures and re-run their tests — the plan flags this inline.
- **Phases 8–9 are gated** on the workflow Postgres job queue (`plans/2026-07-16-workflow-postgres-job-queue/`). If it isn't merged, either land it first or temporarily keep `run-curation!` on the in-process `future` path and defer the async/restart e2e tests (mark them `^:pending`), then revisit.
- **Object storage** (Task 21 raw-blob write): the existing `persistence/filesystem.clj` `DistributedFileSystem` (`put-file`) can store raw HTML at a content-hash path (`curation/<sha256>`) — it is UUID/path-keyed, not sha256-native, but a hash-named key works. Keep the blob write behind `store-raw!` with a `:mock` no-op default so Phases 1–7 never block on it; the `{storage-ref, sha256, size}` value object lives in the stage-run `provenance`.
- **Every task ends green and independently committed.** Run `task test:summary` after each phase.
