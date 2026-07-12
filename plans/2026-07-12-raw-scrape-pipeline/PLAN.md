# Raw Scrape Pipeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve every recipe scrape as an immutable raw corpus plus a versioned, append-only provenance record of each processing run, by reshaping `scrape` into an explicit ACQUIRE → PARSE → NORMALIZE pipeline that persists what it did.

**Architecture:** Three pure stages, each `input-artifact -> StageResult` tagged with its `:technique`, plus a thin `run-pipeline` orchestrator that threads artifacts, folds a provenance ledger, short-circuits on failure, and persists one `raw_scrapes` row + one `processing_runs` row on **both** the success and failure paths. A recipe links to its run via a new nullable FK. Existing extraction/grouping/validation machinery is reused, not rewritten.

**Tech Stack:** Clojure, next.jdbc + HoneySQL, Migratus, Malli, Kaocha + matcher-combinators, embedded-h2 / embedded-postgres for tests, clj-http, Anthropic API (via `workflows.llm-executor`).

## Global Constraints

- **3-layer separation:** no persistence calls from `http_api/`; no HTTP in `api/`. `persistence/scrape_pipeline.clj` is persistence-only.
- **All schema changes via a numbered Migratus `.up.sql`/`.down.sql` pair.** Never alter tables directly. Mirror the H2+Postgres-compatible style of `resources/migrations/20260711000001-add-recipes.up.sql` (statements separated by `--;;`; `UUID DEFAULT gen_random_uuid()`, `JSONB`, `TIMESTAMP WITH TIME ZONE`, composite FKs are all known-good on both backends).
- **SSRF gate is load-bearing.** `fetch-direct` follows redirects manually (`:redirect-strategy :none`) so every hop is SSRF-checked before the fetch. Do NOT delegate redirect-following to clj-http. `java.net.URI` stays only to resolve relative `Location` headers; `java.net.InetAddress` stays for the block-check.
- **`!` suffix on side-effecting fns.** SQL columns `snake_case`, Clojure `kebab-case` (auto-converted).
- **pipeline-version** = `(:revision (kaleidoscope.utils.versioning/get-version-details))` — non-null (`"unknown"` in dev/test).
- **Every feature needs automated tests.** No task ships without coverage.
- **Debugging test failures:** use `task test:summary` / `./bin/test --focus <ns-or-test>`; never pipe full stack traces.

---

## File Structure

**Created:**
- `resources/migrations/20260712000001-add-scrape-pipeline.up.sql` / `.down.sql` — the two tables + the `recipes` link column.
- `src/kaleidoscope/persistence/scrape_pipeline.clj` — inserts + readers for `raw_scrapes` and `processing_runs`. Persistence only.
- `test/kaleidoscope/persistence/scrape_pipeline_test.clj` — round-trip + composite-FK tests on embedded-h2.

**Modified:**
- `src/kaleidoscope/models/recipes.cljc` — new `RawScrape` + `ExtractedFacts` schemas; `ScrapeResult`, `CreateRecipeRequest`, `GetRecipeResponse` gain the run-id.
- `src/kaleidoscope/api/recipe_scraper.clj` — reorganized into `acquire` / `parse` / `normalize` stages + `process` + `run-pipeline`/`extract` orchestrators; `fetch-direct` returns a map.
- `src/kaleidoscope/api/recipes.clj` — `create-recipe!` persists `:scrape-processing-run-id`.
- `src/kaleidoscope/http_api/recipes.clj` — `/scrape` handler builds the pipeline context (db + hostname + api-key + fetcher) and calls `run-pipeline`.
- `test/kaleidoscope/api/recipe_scraper_test.clj` — updated to the new artifact shapes + a `direct` helper + stage/pipeline coverage.
- `test/kaleidoscope/api/recipes_test.clj` — FK round-trip for `create-recipe!` with a run-id.
- `test/kaleidoscope/http_api/recipes_test.clj` — `/scrape` returns the run-id; end-to-end create-with-run-id round-trip.

**Dependency order:** Task 1 (migration) → Task 2 (models) → Task 3 (persistence) → Tasks 4–6 (scraper stages + orchestrator) → Task 7 (recipes api) → Task 8 (http). Tasks 4–6 all live in `recipe_scraper.clj`; do them in order (they build one file).

---

### Task 1: Migration — `raw_scrapes`, `processing_runs`, `recipes` link column

**Files:**
- Create: `resources/migrations/20260712000001-add-scrape-pipeline.up.sql`
- Create: `resources/migrations/20260712000001-add-scrape-pipeline.down.sql`

**Interfaces:**
- Produces: two tables and one column that Tasks 3, 7, 8 read/write. `raw_scrapes (id, hostname)` and `processing_runs (id, hostname)` are composite-unique so they can be composite-FK targets. `recipes.scrape_processing_run_id` FK is `ON DELETE SET NULL`.

- [ ] **Step 1: Write the up migration**

Create `resources/migrations/20260712000001-add-scrape-pipeline.up.sql`:

```sql
-- Raw scrape pipeline. See plans/2026-07-12-raw-scrape-pipeline/DESIGN.md.
--
-- raw_scrapes is the immutable acquisition corpus (one row per fetch, written
-- once). processing_runs is the append-only provenance log: one row per pipeline
-- execution over a raw scrape (re-processing = a new row). recipes links to the
-- run that produced it for full lineage: recipe -> processing_run -> raw_scrape.
CREATE TABLE raw_scrapes (
  id           UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  hostname     VARCHAR NOT NULL,          -- tenant
  request_url  VARCHAR NOT NULL,          -- URL as submitted
  final_url    VARCHAR,                   -- after redirect following (NULL if never fetched)
  http_status  INT,                       -- terminal status (NULL if never fetched)
  fetch_tier   VARCHAR,                   -- 'direct' | 'firecrawl' (NULL on pre-fetch failure)
  raw_html     TEXT,                      -- captured page (NULL on pre-fetch failure)
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (id, hostname)                   -- composite FK target for processing_runs
);

--;;

CREATE TABLE processing_runs (
  id                UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  hostname          VARCHAR NOT NULL,     -- tenant
  raw_scrape_id     UUID NOT NULL,
  pipeline_version  VARCHAR NOT NULL,     -- build/git SHA: identity of the code that ran
  techniques        JSONB,                -- {acquire, parse, normalize} technique kinds
  facts             JSONB,                -- the ExtractedFacts artifact, incl. labels (NULL on early failure)
  content           JSONB,                -- the RecipeContent artifact (NULL on failure)
  llm_calls         JSONB,                -- [{purpose, model, request, response}]; full request stored
  warnings          JSONB,                -- [string]
  outcome           VARCHAR NOT NULL,     -- 'success' | failure reason (bot-blocked, no-recipe-found, ...)
  error_detail      JSONB,                -- {message, reason} on failure (NULL on success)
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (id, hostname),                  -- composite FK target for recipes.scrape_processing_run_id
  FOREIGN KEY (raw_scrape_id, hostname) REFERENCES raw_scrapes (id, hostname) ON DELETE CASCADE
);

--;;

-- Lineage link. Nullable because manually-created recipes have no scrape.
-- ON DELETE SET NULL so deleting a run never cascades away a recipe.
ALTER TABLE recipes ADD COLUMN scrape_processing_run_id UUID;

--;;

ALTER TABLE recipes ADD CONSTRAINT fk_recipes_scrape_processing_run
  FOREIGN KEY (scrape_processing_run_id, hostname)
  REFERENCES processing_runs (id, hostname) ON DELETE SET NULL;

--;;

CREATE INDEX idx_processing_runs_raw_scrape_id ON processing_runs (raw_scrape_id);
```

- [ ] **Step 2: Write the down migration**

Create `resources/migrations/20260712000001-add-scrape-pipeline.down.sql`:

```sql
ALTER TABLE recipes DROP CONSTRAINT fk_recipes_scrape_processing_run;
--;;
ALTER TABLE recipes DROP COLUMN scrape_processing_run_id;
--;;
DROP TABLE processing_runs;
--;;
DROP TABLE raw_scrapes;
```

- [ ] **Step 3: Verify the migration applies on both backends via the existing embedded-DB tests**

The recipe HTTP tests boot embedded-h2 and run every migration; the recipe API tests do the same on embedded-postgres. Run one of each to prove the new migration parses and applies:

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test/create-and-retrieve-recipe-http-test`
Expected: PASS (embedded-h2 ran all migrations including the new one).

Run: `./bin/test --focus kaleidoscope.api.recipes-test/create-and-retrieve-recipe-test`
Expected: PASS (embedded-postgres ran all migrations including the new one).

If either fails with a migration/DDL error, save output to `$SCRATCHPAD/test.log` and `grep "failed to execute\|Syntax error\|Exception:" $SCRATCHPAD/test.log`.

- [ ] **Step 4: Commit**

```bash
git add resources/migrations/20260712000001-add-scrape-pipeline.up.sql resources/migrations/20260712000001-add-scrape-pipeline.down.sql
git commit -m "feat(scrape): add raw_scrapes + processing_runs tables and recipe lineage link

- raw_scrapes: immutable acquisition corpus (one row per fetch)
- processing_runs: append-only provenance log, composite FK to raw_scrapes
- recipes.scrape_processing_run_id: nullable lineage FK, ON DELETE SET NULL

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Model schemas — `RawScrape`, `ExtractedFacts`, run-id fields

**Files:**
- Modify: `src/kaleidoscope/models/recipes.cljc`

**Interfaces:**
- Produces (consumed by Tasks 3–8):
  - `RawScrape` — validated before persistence. Keys: `:hostname :request-url :final-url? :http-status? :fetch-tier? :raw-html?`.
  - `ExtractedFacts` — the unified PARSE artifact validated at the PARSE→NORMALIZE boundary. Keys: `:title :ingredients :steps :section-signals :grouping? :servings? :prep-time-minutes? :cook-time-minutes? :labels`.
  - `ScrapeResult` gains required `[:scrape-processing-run-id :uuid]`.
  - `CreateRecipeRequest` / `GetRecipeResponse` gain optional `[:scrape-processing-run-id [:maybe :uuid]]`.

- [ ] **Step 1: Write failing tests for the new schemas**

Create `test/kaleidoscope/models/recipes_test.clj`:

```clojure
(ns kaleidoscope.models.recipes-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.models.recipes :as models]
            [malli.core :as m]))

(deftest raw-scrape-schema-test
  (testing "a full raw scrape validates; a pre-fetch-failure (only request-url) validates too"
    (is (m/validate models/RawScrape
                    {:hostname "andrewslai.com" :request-url "http://x/r"
                     :final-url "http://x/r" :http-status 200
                     :fetch-tier "direct" :raw-html "<html/>"}))
    (is (m/validate models/RawScrape
                    {:hostname "andrewslai.com" :request-url "http://x/r"}))
    (is (not (m/validate models/RawScrape {:request-url "http://x/r"})))))

(deftest extracted-facts-schema-test
  (testing "json-ld facts (no grouping) and llm facts (with grouping) both validate"
    (is (m/validate models/ExtractedFacts
                    {:title "Soup" :ingredients ["water"] :steps ["Boil"]
                     :section-signals [] :grouping nil :labels []}))
    (is (m/validate models/ExtractedFacts
                    {:title "Cake" :ingredients ["flour" "sugar"] :steps ["Mix"]
                     :section-signals [] :labels ["dessert"]
                     :grouping [{:name "Cake" :ingredients [0 1] :steps [0]}]}))))

(deftest scrape-result-carries-run-id-test
  (is (m/validate models/ScrapeResult
                  {:recipe {:title "X" :sections [{:ingredients [] :steps []}]}
                   :suggested-labels [] :extraction-method "json-ld" :warnings []
                   :scrape-processing-run-id (random-uuid)})))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.models.recipes-test`
Expected: FAIL — `RawScrape` / `ExtractedFacts` unresolved, and `ScrapeResult` rejects `:scrape-processing-run-id`.

- [ ] **Step 3: Add the schemas**

In `src/kaleidoscope/models/recipes.cljc`, add `RawScrape` and `ExtractedFacts` after `RecipeContent` (before `RecipeLabel`):

```clojure
(def RawScrape
  ;; Validated before persistence. All fetch fields are nullable so a pre-fetch
  ;; failure (SSRF block) still records request-url + hostname in the corpus.
  [:map
   [:hostname    :string]
   [:request-url :string]
   [:final-url   {:optional true} [:maybe :string]]
   [:http-status {:optional true} [:maybe :int]]
   [:fetch-tier  {:optional true} [:maybe :string]]
   [:raw-html    {:optional true} [:maybe :string]]])

;; The unified PARSE artifact. Both techniques emit this shape; NORMALIZE
;; dispatches on what it carries. `:grouping` (section name + indexes into the
;; flat lists) is present only on the LLM path; `:section-signals` (candidate
;; section names) only on the JSON-LD path.
(def ExtractedFacts
  [:map
   [:title            [:maybe :string]]
   [:ingredients      [:sequential :string]]
   [:steps            [:sequential :string]]
   [:section-signals  [:sequential :string]]
   [:grouping         {:optional true}
    [:maybe [:sequential [:map
                          [:name        {:optional true} [:maybe :string]]
                          [:ingredients [:sequential :int]]
                          [:steps       [:sequential :int]]]]]]
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]
   [:labels            [:sequential :string]]])
```

Then edit `ScrapeResult` to add the run-id (required — the HTTP path always supplies it):

```clojure
(def ScrapeResult
  [:map
   [:recipe            RecipeContent]
   [:suggested-labels  [:sequential :string]]
   [:extraction-method [:enum "json-ld" "json-ld+llm-sections" "llm"]]
   [:warnings          [:sequential :string]]
   [:scrape-processing-run-id :uuid]])
```

Edit `CreateRecipeRequest` — add inside the `[:map ...]`:

```clojure
   [:scrape-processing-run-id {:optional true} [:maybe :uuid]]
```

Edit `GetRecipeResponse` — add inside the `[:map ...]`:

```clojure
   [:scrape-processing-run-id {:optional true} [:maybe :uuid]]
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.models.recipes-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/models/recipes.cljc test/kaleidoscope/models/recipes_test.clj
git commit -m "feat(recipes): add RawScrape + ExtractedFacts schemas, thread run-id through result/request/response

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Persistence — `scrape_pipeline.clj`

**Files:**
- Create: `src/kaleidoscope/persistence/scrape_pipeline.clj`
- Create: `test/kaleidoscope/persistence/scrape_pipeline_test.clj`

**Interfaces:**
- Consumes: `kaleidoscope.persistence.rdbms` (`insert!`, `find-by-keys` — handle JSONB via the SettableParameter/ReadableColumn extensions already in `rdbms.clj`); `kaleidoscope.utils.core` (`uuid`, `now`).
- Produces (consumed by Task 6):
  - `(create-raw-scrape! db raw)` → inserted row incl. `:id` (a `java.util.UUID`). `raw` keys: `:hostname :request-url :final-url? :http-status? :fetch-tier? :raw-html?`.
  - `(create-processing-run! db run)` → inserted row incl. `:id`. `run` keys: `:hostname :raw-scrape-id :pipeline-version :techniques :facts :content :llm-calls :warnings :outcome :error-detail`.
  - `(get-raw-scrape db id hostname)` → row or nil.
  - `(get-processing-run db id hostname)` → row or nil.

- [ ] **Step 1: Write failing round-trip + FK tests**

Create `test/kaleidoscope/persistence/scrape_pipeline_test.clj`:

```clojure
(ns kaleidoscope.persistence.scrape-pipeline-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
            [matcher-combinators.test :refer [match?]]))

(def host "andrewslai.com")

(defn- seed-raw! [db & {:as overrides}]
  (pipeline-db/create-raw-scrape!
   db (merge {:hostname host :request-url "http://example.com/r"
              :final-url "http://example.com/r" :http-status 200
              :fetch-tier "direct" :raw-html "<html>recipe</html>"}
             overrides)))

(deftest raw-scrape-round-trip-test
  (let [db (embedded-h2/fresh-db!)
        big (apply str (repeat 200000 "x"))          ;; large raw_html (~200 KB)
        {:keys [id]} (seed-raw! db :raw-html big)]
    (testing "reads back by id+hostname, incl. a large raw_html and all fetch fields"
      (is (match? {:request-url "http://example.com/r" :final-url "http://example.com/r"
                   :http-status 200 :fetch-tier "direct" :raw-html big}
                  (pipeline-db/get-raw-scrape db id host))))
    (testing "scoped to hostname"
      (is (nil? (pipeline-db/get-raw-scrape db id "other.com"))))))

(deftest pre-fetch-failure-raw-scrape-test
  (let [db (embedded-h2/fresh-db!)
        {:keys [id]} (pipeline-db/create-raw-scrape!
                      db {:hostname host :request-url "http://169.254.169.254/"})]
    (testing "a raw scrape with only request-url persists (fetch fields null)"
      (is (match? {:request-url "http://169.254.169.254/"
                   :final-url nil? :http-status nil? :raw-html nil?}
                  (pipeline-db/get-raw-scrape db id host))))))

(deftest processing-run-round-trip-test
  (let [db (embedded-h2/fresh-db!)
        {raw-id :id} (seed-raw! db)
        {run-id :id}
        (pipeline-db/create-processing-run!
         db {:hostname host :raw-scrape-id raw-id :pipeline-version "abc123"
             :techniques {:acquire :direct :parse :json-ld :normalize :llm-grouping}
             :facts   {:title "Cake" :ingredients ["flour"] :steps ["Mix"]
                       :section-signals [] :labels ["dessert"]}
             :content {:title "Cake" :sections [{:name "Cake" :ingredients ["flour"] :steps ["Mix"]}]}
             :llm-calls [{:purpose "normalize" :model "claude-haiku-4-5"
                          :request {:system "group" :messages []} :response {:content []}}]
             :warnings ["a warning"]
             :outcome :success :error-detail nil})]
    (testing "JSONB fields round-trip decoded (techniques, facts, content, llm_calls, warnings)"
      (is (match? {:raw-scrape-id raw-id :pipeline-version "abc123"
                   :techniques {:acquire "direct" :parse "json-ld" :normalize "llm-grouping"}
                   :facts   {:title "Cake" :labels ["dessert"]}
                   :content {:title "Cake" :sections [{:name "Cake"}]}
                   :llm-calls [{:purpose "normalize" :model "claude-haiku-4-5"}]
                   :warnings ["a warning"]
                   :outcome "success"}
                  (pipeline-db/get-processing-run db run-id host))))))

(deftest processing-run-links-to-raw-scrape-test
  (let [db (embedded-h2/fresh-db!)
        {raw-id :id} (seed-raw! db)
        {run-id :id} (pipeline-db/create-processing-run!
                      db {:hostname host :raw-scrape-id raw-id :pipeline-version "v"
                          :techniques {} :outcome :bot-blocked
                          :error-detail {:message "blocked" :reason "bot-blocked"}})]
    (testing "the run's raw_scrape_id resolves to the stored raw scrape (composite FK link)"
      (let [{:keys [raw-scrape-id]} (pipeline-db/get-processing-run db run-id host)]
        (is (= raw-id raw-scrape-id))
        (is (some? (pipeline-db/get-raw-scrape db raw-scrape-id host)))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.persistence.scrape-pipeline-test`
Expected: FAIL — namespace `kaleidoscope.persistence.scrape-pipeline` does not exist.

- [ ] **Step 3: Implement the persistence namespace**

Create `src/kaleidoscope/persistence/scrape_pipeline.clj`:

```clojure
(ns kaleidoscope.persistence.scrape-pipeline
  "Persistence for the raw scrape pipeline. Two append-only tables: `raw_scrapes`
  (immutable acquisition corpus) and `processing_runs` (provenance log). JSONB
  columns are handled by the SettableParameter/ReadableColumn extensions in
  `persistence.rdbms`. Persistence only — no HTTP, no domain logic. See
  plans/2026-07-12-raw-scrape-pipeline/DESIGN.md."
  (:require [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.utils.core :as utils]))

(defn create-raw-scrape!
  "Insert one immutable raw_scrapes row; return the created row (incl. :id).
  Fetch fields are optional — a pre-fetch failure records request-url only."
  [db {:keys [hostname request-url final-url http-status fetch-tier raw-html]}]
  (first (rdbms/insert! db :raw-scrapes
                        {:id          (utils/uuid)
                         :hostname    hostname
                         :request-url request-url
                         :final-url   final-url
                         :http-status http-status
                         :fetch-tier  fetch-tier
                         :raw-html    raw-html
                         :created-at  (utils/now)}
                        :ex-subtype :UnableToCreateRawScrape)))

(defn create-processing-run!
  "Insert one processing_runs row; return the created row (incl. :id). `outcome`
  may be a keyword or string; stored as its name. JSONB fields (`techniques`,
  `facts`, `content`, `llm-calls`, `warnings`, `error-detail`) are passed as
  Clojure data and serialized by the rdbms parameter extensions."
  [db {:keys [hostname raw-scrape-id pipeline-version techniques facts content
              llm-calls warnings outcome error-detail]}]
  (first (rdbms/insert! db :processing-runs
                        {:id               (utils/uuid)
                         :hostname         hostname
                         :raw-scrape-id    raw-scrape-id
                         :pipeline-version pipeline-version
                         :techniques       techniques
                         :facts            facts
                         :content          content
                         :llm-calls        (vec (or llm-calls []))
                         :warnings         (vec (or warnings []))
                         :outcome          (name outcome)
                         :error-detail     error-detail
                         :created-at       (utils/now)}
                        :ex-subtype :UnableToCreateProcessingRun)))

(defn get-raw-scrape
  "One raw scrape by id, scoped to hostname."
  [db id hostname]
  (first (rdbms/find-by-keys db :raw-scrapes {:id id :hostname hostname})))

(defn get-processing-run
  "One processing run by id, scoped to hostname."
  [db id hostname]
  (first (rdbms/find-by-keys db :processing-runs {:id id :hostname hostname})))
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.persistence.scrape-pipeline-test`
Expected: PASS. If a JSONB column reads back as a raw string instead of decoded data, confirm the column is declared `JSONB` (not `TEXT`) in the migration — `raw_html` is the only `TEXT` column and is expected to read back as a plain string.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/persistence/scrape_pipeline.clj test/kaleidoscope/persistence/scrape_pipeline_test.clj
git commit -m "feat(scrape): persistence for raw_scrapes and processing_runs

- create-raw-scrape! / create-processing-run! inserts returning the row
- get-* readers scoped by id+hostname
- round-trip + composite-FK-link tests on embedded-h2

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Scraper stage — ACQUIRE + rich `fetch-direct`

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj`
- Modify: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Consumes: existing `fetch-once`, `safe-url?`, `location-header`, `firecrawl/fetch-rendered`.
- Produces (consumed by Task 6):
  - `fetch-direct` now returns `{:raw-html String :final-url String :http-status int}` (was: the body string). SSRF/redirect/status classification unchanged; still throws `{:type :scrape :reason ...}`.
  - `(acquire {:fetcher f :url url})` → StageResult. Success: `{:artifact {:request-url :final-url :http-status :fetch-tier :raw-html} :technique :direct|:firecrawl}`. Failure (`:type :scrape`): `{:outcome reason :error-detail {:message :reason} :raw {:request-url url}}`. Non-`:scrape` exceptions propagate.

- [ ] **Step 1: Add a `direct` test helper and update `fetch-direct`'s expected shape**

At the top of `test/kaleidoscope/api/recipe_scraper_test.clj` (after `public-url`), add a helper that builds the new `fetch-direct` return, and use it everywhere a test stubs `fetch-direct` to inject HTML:

```clojure
(defn- direct
  "Build the map fetch-direct now returns, for stubbing at the fetch boundary."
  [html]
  {:raw-html html :final-url public-url :http-status 200})
```

Rewrite `fetch-direct-status-classification-test` to expect the map on success (the 403/429/503/404 branches are unchanged — they still throw):

```clojure
(deftest fetch-direct-status-classification-test
  (testing "fetch-direct maps HTTP status to scrape reasons at the clj-http boundary"
    (with-redefs [http/get (fn [_ _] {:status 200 :headers {} :body "<html>ok</html>"})]
      (is (match? {:raw-html "<html>ok</html>" :http-status 200}
                  (scraper/fetch-direct public-url))))
    (doseq [blocked-status [403 429 503]]
      (with-redefs [http/get (fn [_ _] {:status blocked-status :headers {} :body "nope"})]
        (is (match? {:reason :bot-blocked}
                    (try (scraper/fetch-direct public-url)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))))
    (with-redefs [http/get (fn [_ _] {:status 404 :headers {} :body "nope"})]
      (is (match? {:reason :fetch-failed}
                  (try (scraper/fetch-direct public-url)
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))
```

Add an ACQUIRE test:

```clojure
(deftest acquire-produces-raw-scrape-artifact-test
  (testing "a successful direct fetch yields a RawScrape artifact tagged :direct"
    (with-redefs [scraper/fetch-direct (fn [_] (direct json-ld-html))]
      (is (match? {:artifact  {:request-url public-url :fetch-tier "direct"
                               :http-status 200 :raw-html json-ld-html}
                   :technique :direct}
                  (scraper/acquire {:fetcher nil :url public-url})))))
  (testing "an SSRF block yields an :outcome + partial raw (request-url only)"
    (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :blocked-url})))]
      (is (match? {:outcome :blocked-url
                   :error-detail {:reason :blocked-url}
                   :raw {:request-url "http://169.254.169.254/"}}
                  (scraper/acquire {:fetcher nil :url "http://169.254.169.254/"}))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/acquire-produces-raw-scrape-artifact-test`
Expected: FAIL — `acquire` unresolved.

- [ ] **Step 3: Update `fetch-direct` to return a map, `fetch-html` to carry the tier, add `acquire`**

In `src/kaleidoscope/api/recipe_scraper.clj`, change the `:else` branch of `fetch-direct` to return the map (rest of the fn unchanged):

```clojure
        :else
        (let [body (or body "")
              html (if (> (count body) max-body-bytes) (subs body 0 max-body-bytes) body)]
          {:raw-html html :final-url (str uri) :http-status status})))))
```

Update `fetch-html` to attach `:fetch-tier` and normalize the firecrawl path to the same map shape:

```clojure
(defn- fetch-html
  "Fetch tiers: direct fetch first (free); on a bot block, retry through the
  rendering `fetcher` when one is configured. Returns
  {:raw-html :final-url :http-status :fetch-tier}. Any other failure — and a bot
  block with no fetcher — propagates."
  [fetcher url]
  (try
    (assoc (fetch-direct url) :fetch-tier "direct")
    (catch clojure.lang.ExceptionInfo e
      (if (and (= :bot-blocked (:reason (ex-data e))) fetcher)
        (do (log/infof "Direct fetch bot-blocked; retrying %s via rendering fetcher" url)
            {:raw-html    (firecrawl/fetch-rendered fetcher url)
             :final-url   url
             :http-status 200
             :fetch-tier  "firecrawl"})
        (throw e)))))
```

Add the ACQUIRE stage in the "Entry point" section (replacing the old `scrape` fn is done in Task 6; add `acquire` above it now):

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; ACQUIRE stage
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn acquire
  "ACQUIRE: fetch the page (SSRF-guarded), direct then rendering fallback.
  StageResult on success: {:artifact RawScrape-data :technique :direct|:firecrawl}.
  On a :type :scrape failure: {:outcome reason :error-detail {..} :raw {:request-url url}}
  so the orchestrator still records an abandoned scrape. Other exceptions propagate."
  [{:keys [fetcher url]}]
  (try
    (let [{:keys [raw-html final-url http-status fetch-tier]} (fetch-html fetcher url)]
      {:artifact  {:request-url url
                   :final-url   final-url
                   :http-status http-status
                   :fetch-tier  fetch-tier
                   :raw-html    raw-html}
       :technique (keyword fetch-tier)})
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type reason]} (ex-data e)]
        (if (= :scrape type)
          {:outcome      reason
           :error-detail {:message (ex-message e) :reason reason}
           :raw          {:request-url url}}
          (throw e))))))
```

- [ ] **Step 4: Update every remaining `fetch-direct` HTML stub in the test file to use `direct`**

Replace each `(with-redefs [scraper/fetch-direct (fn [_] <html-string>)] ...)` where the stub returns HTML with `(with-redefs [scraper/fetch-direct (fn [_] (direct <html-string>))] ...)`. The affected tests: `unsectioned-scrape-assembles-single-section-test`, `llm-fallback-signal-test`, `llm-fallback-invoked-test`, `llm-fallback-empty-sections-guard-test`, `sectioned-scrape-groups-with-llm-test`, `invalid-grouping-falls-back-test`, `header-ingredient-lines-trigger-grouping-test`, `sectioned-without-api-key-flattens-with-warning-test`, `dropped-header-lines-surface-as-warning-test`. Stubs that **throw** (`bot-blocked-*`, `fetcher-render-failure-*`) are unchanged. These tests still call `scraper/scrape` — leave that name for now; Task 6 renames it to `extract`.

- [ ] **Step 5: Run to verify ACQUIRE + fetch-direct pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/acquire-produces-raw-scrape-artifact-test`
Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/fetch-direct-status-classification-test`
Expected: PASS both. (The extraction tests calling `scraper/scrape` will be finished in Task 6.)

- [ ] **Step 6: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "refactor(scraper): fetch-direct returns raw+final-url+status; add ACQUIRE stage

- fetch-direct: String -> {:raw-html :final-url :http-status}, SSRF gate unchanged
- fetch-html threads :fetch-tier (direct|firecrawl)
- acquire stage emits a RawScrape artifact tagged with its tier

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Scraper stages — PARSE + NORMALIZE producing typed artifacts

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj`
- Modify: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Consumes: `parse-json-ld` (now emitting `ExtractedFacts`), `valid-grouping?`, `grouping->sections`, `sectioned?`, `single-section`, `html->text`, `extract-prompt`, `grouping-prompt`.
- Produces (consumed by Task 6):
  - `parse-json-ld` returns facts with `:section-signals` (was `:section-names`), `:labels` (was `:suggested-labels`), and `:grouping nil`.
  - `(parse {:api-key k :raw-html h})` → StageResult: `{:artifact ExtractedFacts :technique :json-ld}`; or `{:artifact facts :technique :llm :llm-calls [..] :warnings [..]}`; or `{:outcome :no-recipe-found :error-detail {..}}` when no facts and no api-key.
  - `(normalize {:api-key k :facts f})` → StageResult: `{:artifact RecipeContent :technique :pre-grouped|:llm-grouping|:single-section :llm-calls? :warnings?}`. Always produces content.

- [ ] **Step 1: Update JSON-LD fact-shape assertions and add PARSE/NORMALIZE tests**

In `test/kaleidoscope/api/recipe_scraper_test.clj`, update the three `parse-json-ld` assertions to the new keys:

```clojure
(deftest json-ld-happy-path-test
  (is (match? {:title             "Chana Masala"
               :ingredients       ["2 cups chickpeas" "1 tbsp flour"]
               :steps             ["Soak" "Cook"]
               :section-signals   []
               :grouping          nil?
               :servings          "4"
               :prep-time-minutes 15
               :cook-time-minutes 30
               :labels            #(contains? (set %) "Indian")}
              (scraper/parse-json-ld json-ld-html))))

(deftest json-ld-graph-wrapper-test
  (let [html "<script type='application/ld+json'>{\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"water\"],\"recipeInstructions\":\"Boil water\"}]}</script>"]
    (is (match? {:title "Soup" :steps ["Boil water"] :section-signals []}
                (scraper/parse-json-ld html)))))

(deftest json-ld-howto-section-test
  (testing "HowToSection names become candidate section-signals; steps stay verbatim and ordered"
    (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeIngredient\":[],\"recipeInstructions\":[{\"@type\":\"HowToSection\",\"name\":\"Cake\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"A\"},{\"@type\":\"HowToStep\",\"text\":\"B\"}]},{\"@type\":\"HowToSection\",\"name\":\"Frosting\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"C\"}]}]}</script>"]
      (is (match? {:steps ["A" "B" "C"] :section-signals ["Cake" "Frosting"]}
                  (scraper/parse-json-ld html))))))
```

Add stage-level tests:

```clojure
(deftest parse-stage-json-ld-then-llm-test
  (testing "PARSE prefers JSON-LD; without JSON-LD and without an api-key it fails"
    (is (match? {:technique :json-ld :artifact {:title "Chana Masala"}}
                (scraper/parse {:api-key "sk-test" :raw-html json-ld-html})))
    (is (match? {:outcome :no-recipe-found}
                (scraper/parse {:api-key nil :raw-html "<html>no structured data</html>"}))))
  (testing "without JSON-LD but with an api-key, PARSE returns :llm facts with flat lists + grouping + the call"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\",\"beef\"],\"steps\":[\"Simmer\"]}],\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:technique :llm
                   :artifact  {:title "Stew" :ingredients ["carrots" "beef"] :steps ["Simmer"]
                               :grouping [{:name nil? :ingredients [0 1] :steps [0]}]
                               :labels ["comfort"]}
                   :llm-calls [{:purpose :parse :model "claude-haiku-4-5"
                                :request map? :response map?}]}
                  (scraper/parse {:api-key "sk-test" :raw-html "<html>stew</html>"}))))))

(deftest normalize-stage-dispatch-test
  (let [flat {:title "Cake" :ingredients ["2 cups flour" "1 cup sugar" "1 cup butter"]
              :steps ["Mix" "Bake" "Whip"] :section-signals [] :labels []}]
    (testing "grouping present -> :pre-grouped deterministic merge, no LLM call"
      (with-redefs [llm/post-anthropic-sync (fn [_ _] (throw (ex-info "must not call" {})))]
        (is (match? {:technique :pre-grouped
                     :artifact  {:sections [{:name "Cake"     :ingredients ["2 cups flour" "1 cup sugar"] :steps ["Mix" "Bake"]}
                                            {:name "Frosting" :ingredients ["1 cup butter"]               :steps ["Whip"]}]}}
                    (scraper/normalize {:api-key nil
                                        :facts (assoc flat :grouping
                                                      [{:name "Cake" :ingredients [0 1] :steps [0 1]}
                                                       {:name "Frosting" :ingredients [2] :steps [2]}])})))))
    (testing "no signals -> :single-section, no LLM call"
      (with-redefs [llm/post-anthropic-sync (fn [_ _] (throw (ex-info "must not call" {})))]
        (is (match? {:technique :single-section
                     :artifact  {:sections [{:name nil? :ingredients ["2 cups flour" "1 cup sugar" "1 cup butter"]}]}}
                    (scraper/normalize {:api-key "sk-test" :facts flat})))))
    (testing "section-signals + api-key -> :llm-grouping, records the call"
      (with-redefs [llm/post-anthropic-sync (fn [_ _] {:content [{:text valid-grouping-json}]})]
        (is (match? {:technique :llm-grouping
                     :llm-calls [{:purpose :normalize :request map? :response map?}]
                     :artifact  {:sections [{:name "Cake"} {:name "Frosting"}]}}
                    (scraper/normalize {:api-key "sk-test"
                                        :facts (assoc flat :section-signals ["Cake" "Frosting"])})))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/parse-stage-json-ld-then-llm-test`
Expected: FAIL — `parse`/`normalize` unresolved and `parse-json-ld` still emits `:section-names`/`:suggested-labels`.

- [ ] **Step 3: Rework `parse-json-ld`, `group-sections-with-llm`, add `parse`/`parse-with-llm`/`normalize`**

In `src/kaleidoscope/api/recipe_scraper.clj`:

Update `parse-json-ld`'s returned map keys (rename `:section-names` → `:section-signals`, `:suggested-labels` → `:labels`, add `:grouping nil`):

```clojure
(defn parse-json-ld
  "Extract verbatim ExtractedFacts from JSON-LD, or nil if no Recipe (or none
  with a name). Facts, not a draft: NORMALIZE decides how facts become sections."
  [html]
  (when-let [node (find-recipe-node (ld-json-blocks html))]
    (when-not (str/blank? (:name node))
      (let [{:keys [steps section-names]} (parse-instructions (:recipeInstructions node))]
        {:title             (:name node)
         :ingredients       (vec (:recipeIngredient node))
         :steps             steps
         :section-signals   section-names
         :grouping          nil
         :servings          (some-> (first-or-self (:recipeYield node)) str)
         :prep-time-minutes (iso-duration->minutes (:prepTime node))
         :cook-time-minutes (iso-duration->minutes (:cookTime node))
         :labels            (->suggested-labels node)}))))
```

Update `sectioned?` to read `:section-signals`:

```clojure
(defn- sectioned?
  [{:keys [section-signals ingredients]}]
  (boolean (or (seq section-signals)
               (some header-like? ingredients))))
```

Replace `group-sections-with-llm` so it reads `:section-signals`, builds the request explicitly, and returns the call for the ledger. It returns `{:sections .. :dropped .. :llm-call ..}` on a valid grouping, `{:sections nil :llm-call ..}` when a call was made but the grouping was invalid, or `nil` on exception:

```clojure
(defn- group-sections-with-llm
  "Ask for a grouping and merge it deterministically. Returns
  {:sections [..] :dropped [omitted-line ..] :llm-call {..}} on a valid grouping;
  {:sections nil :llm-call {..}} when the call succeeded but the grouping was
  unusable (caller flattens but the call is still recorded); nil on exception."
  [api-key {:keys [ingredients steps section-signals] :as facts}]
  (try
    (let [user     (str "INGREDIENTS:\n" (numbered ingredients)
                        "\n\nSTEPS:\n" (numbered steps)
                        (when (seq section-signals)
                          (str "\n\nCANDIDATE SECTION NAMES:\n"
                               (str/join "\n" section-signals))))
          request  {:model      fallback-model
                    :max_tokens 1024
                    :system     grouping-prompt
                    :messages   [{:role "user" :content user}]}
          response (llm/post-anthropic-sync api-key request)
          parsed   (json/decode (llm/extract-json (-> response :content first :text)) true)
          llm-call {:purpose :normalize :model fallback-model :request request :response response}]
      (if (valid-grouping? facts (:sections parsed))
        (let [assigned (set (mapcat :ingredients (:sections parsed)))
              dropped  (vec (keep-indexed (fn [i line] (when-not (assigned i) line)) ingredients))]
          {:sections (grouping->sections facts (:sections parsed))
           :dropped  dropped
           :llm-call llm-call})
        {:sections nil :llm-call llm-call}))
    (catch Exception e
      (log/warnf "Section grouping failed: %s" (ex-message e))
      nil)))
```

Replace `facts->result` with `facts->content` (returns just the `RecipeContent`):

```clojure
(defn- facts->content
  [{:keys [title servings prep-time-minutes cook-time-minutes]} sections]
  {:title             title
   :sections          sections
   :servings          servings
   :prep-time-minutes prep-time-minutes
   :cook-time-minutes cook-time-minutes})
```

Rework the LLM fallback into a PARSE stage that emits `ExtractedFacts`. Replace `extract-with-llm` with `parse-with-llm` (flattens the LLM's sections into flat lists + a `:grouping` of index ranges, and carries the call + no-sections warning on the StageResult):

```clojure
(defn- parse-with-llm
  "LLM PARSE: ask for sections, then flatten to ExtractedFacts (flat ingredient/
  step lists + a :grouping of index ranges NORMALIZE merges deterministically).
  The full request is recorded so the technique's version is recoverable."
  [api-key html]
  (let [text     (subs (html->text html) 0 (min 50000 (count (html->text html))))
        request  {:model      fallback-model
                  :max_tokens 2048
                  :system     extract-prompt
                  :messages   [{:role "user" :content text}]}
        response (llm/post-anthropic-sync api-key request)
        parsed   (json/decode (llm/extract-json (-> response :content first :text)) true)
        raw-secs (:sections parsed)
        sections (if (sequential? raw-secs)
                   (mapv (fn [{:keys [name ingredients steps]}]
                           {:name name :ingredients (vec ingredients) :steps (vec steps)})
                         raw-secs)
                   [])
        flat-ing (vec (mapcat :ingredients sections))
        flat-stp (vec (mapcat :steps sections))
        grouping (loop [secs sections, i 0, j 0, acc []]
                   (if (empty? secs)
                     acc
                     (let [{:keys [name ingredients steps]} (first secs)
                           ni (count ingredients) ns (count steps)]
                       (recur (rest secs) (+ i ni) (+ j ns)
                              (conj acc {:name        name
                                         :ingredients (vec (range i (+ i ni)))
                                         :steps       (vec (range j (+ j ns)))})))))
        no-secs? (empty? sections)]
    {:artifact  {:title             (:title parsed)
                 :ingredients       flat-ing
                 :steps             flat-stp
                 :section-signals   []
                 :grouping          (if no-secs? [{:name nil :ingredients [] :steps []}] grouping)
                 :servings          (:servings parsed)
                 :prep-time-minutes (:prep_time_minutes parsed)
                 :cook-time-minutes (:cook_time_minutes parsed)
                 :labels            (vec (:suggested_labels parsed))}
     :technique :llm
     :llm-calls [{:purpose :parse :model fallback-model :request request :response response}]
     :warnings  (if no-secs? ["LLM returned no sections"] [])}))

(defn parse
  "PARSE: RawScrape html -> ExtractedFacts. JSON-LD first; LLM fallback when an
  api-key is present; :no-recipe-found when neither yields facts."
  [{:keys [api-key raw-html]}]
  (if-let [facts (parse-json-ld raw-html)]
    {:artifact facts :technique :json-ld}
    (if api-key
      (parse-with-llm api-key raw-html)
      {:outcome      :no-recipe-found
       :error-detail {:message "No recipe found and no LLM available"
                      :reason  :no-recipe-found}})))

(defn normalize
  "NORMALIZE: ExtractedFacts -> RecipeContent. Dispatch: grouping present ->
  :pre-grouped (deterministic index merge); section signals + api-key ->
  :llm-grouping (constrained LLM grouping, flattening on failure); else ->
  :single-section. Always produces content."
  [{:keys [api-key facts]}]
  (let [content (fn [sections] (facts->content facts sections))]
    (cond
      (:grouping facts)
      {:artifact (content (grouping->sections facts (:grouping facts)))
       :technique :pre-grouped}

      (sectioned? facts)
      (if api-key
        (let [{:keys [sections dropped llm-call]} (group-sections-with-llm api-key facts)]
          (if sections
            {:artifact  (content sections)
             :technique :llm-grouping
             :llm-calls (vec (when llm-call [llm-call]))
             :warnings  (when (seq dropped)
                          [(str "Ingredient lines treated as section headers, not ingredients: "
                                (str/join " | " dropped))])}
            {:artifact  (content (single-section facts))
             :technique :single-section
             :llm-calls (vec (when llm-call [llm-call]))
             :warnings  ["Sectioned recipe but grouping failed; flattened to one section"]}))
        {:artifact  (content (single-section facts))
         :technique :single-section
         :warnings  ["Sectioned recipe but no LLM available; flattened to one section"]})

      :else
      {:artifact  (content (single-section facts))
       :technique :single-section})))
```

Note: `single-section` currently destructures `{:keys [ingredients steps]}` — unchanged, still valid on `ExtractedFacts`. Leave the old `scrape` fn in place for now (Task 6 replaces it); it references `facts->result`, so temporarily keep a shim: **do not delete `facts->result` until Task 6.** If the compiler errors on the now-removed `facts->result`, keep the old `scrape` + `facts->result` untouched below the new stage fns and let Task 6 remove them together. (Cleanest: in this task, add the new fns; in Task 6, delete `scrape` + `facts->result`.)

- [ ] **Step 4: Run to verify PARSE/NORMALIZE pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/parse-stage-json-ld-then-llm-test`
Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/normalize-stage-dispatch-test`
Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/json-ld-happy-path-test`
Expected: PASS all three.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "refactor(scraper): unify PARSE + NORMALIZE stages over ExtractedFacts

- parse-json-ld emits section-signals/labels/grouping (the unified fact shape)
- parse-with-llm flattens LLM sections to flat lists + a grouping of index ranges
- normalize dispatches pre-grouped / llm-grouping / single-section, always emits content
- grouping call is captured for the provenance ledger

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: Orchestrator — `process`, `run-pipeline` (persist), `extract`

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj`
- Modify: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Consumes: `acquire`, `parse`, `normalize` (Tasks 4–5); `pipeline-db/create-raw-scrape!`, `pipeline-db/create-processing-run!` (Task 3); `vu/get-version-details`.
- Produces (consumed by Task 8):
  - `(run-pipeline {:database db :hostname h :api-key k :fetcher f} url)` → `ScrapeResult` + `:scrape-processing-run-id`. Persists one raw + one run on **both** success and failure; re-throws `{:type :scrape :reason ...}` on failure so the handler's 422 mapping is unchanged.
  - `(extract {:api-key k :fetcher f} url)` → `ScrapeResult` (no run-id, no persistence); throws on failure. Replaces the old `scrape` for extraction-only tests.

- [ ] **Step 1: Rewrite the remaining orchestrator-level tests to call `extract`, and add a `run-pipeline` persistence test**

In `test/kaleidoscope/api/recipe_scraper_test.clj`:

Add the persistence require and a `host` def at the top:

```clojure
            [kaleidoscope.persistence.rdbms.embedded-h2-impl :as embedded-h2]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
```
```clojure
(def ^:private host "andrewslai.com")
```

Rename every `(scraper/scrape <ctx> <url>)` call in these tests to `(scraper/extract <ctx> <url>)` (same args, same expected `ScrapeResult` shape): `unsectioned-scrape-assembles-single-section-test`, `llm-fallback-signal-test`, `llm-fallback-invoked-test`, `llm-fallback-empty-sections-guard-test`, `bot-blocked-falls-back-to-fetcher-test`, `bot-blocked-without-fetcher-surfaces-test`, `fetcher-render-failure-propagates-test`, `sectioned-scrape-groups-with-llm-test`, `invalid-grouping-falls-back-test`, `header-ingredient-lines-trigger-grouping-test`, `sectioned-without-api-key-flattens-with-warning-test`, `dropped-header-lines-surface-as-warning-test`.

Add persistence-path tests:

```clojure
(deftest run-pipeline-persists-success-test
  (testing "a successful scrape persists raw + run and returns a resolvable run-id"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [scraper/fetch-direct (fn [_] (direct json-ld-html))]
        (let [{:keys [scrape-processing-run-id] :as result}
              (scraper/run-pipeline {:database db :hostname host :api-key nil :fetcher nil} public-url)]
          (is (match? {:recipe {:title "Chana Masala"}
                       :extraction-method "json-ld"
                       :scrape-processing-run-id uuid?}
                      result))
          (testing "the run is stored with a non-null pipeline_version, technique tags, and content"
            (let [run (pipeline-db/get-processing-run db scrape-processing-run-id host)]
              (is (match? {:pipeline-version string?
                           :techniques {:acquire "direct" :parse "json-ld" :normalize "single-section"}
                           :content {:title "Chana Masala"}
                           :outcome "success"}
                          run))
              (testing "its raw_scrape_id resolves to the stored HTML"
                (is (match? {:raw-html json-ld-html}
                            (pipeline-db/get-raw-scrape db (:raw-scrape-id run) host)))))))))))

(deftest run-pipeline-records-llm-calls-test
  (testing "the pre-grouped LLM path records :pre-grouped with a populated llm_calls storing the full request"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [scraper/fetch-direct    (fn [_] (direct "<html>Grandma's stew: carrots, beef. Simmer.</html>"))
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\"],\"steps\":[\"Simmer\"]}],\"suggested_labels\":[]}"}]})]
        (let [{:keys [scrape-processing-run-id]}
              (scraper/run-pipeline {:database db :hostname host :api-key "sk-test" :fetcher nil} public-url)
              run (pipeline-db/get-processing-run db scrape-processing-run-id host)]
          (is (match? {:techniques {:parse "llm" :normalize "pre-grouped"}
                       :llm-calls [{:purpose "parse" :model "claude-haiku-4-5"
                                    :request {:system string? :model "claude-haiku-4-5"}}]}
                      run)))))))

(deftest run-pipeline-persists-failure-and-rethrows-test
  (testing "a fetch failure persists a run with the outcome and no content, then re-throws for the handler"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :bot-blocked})))]
        (is (match? {:reason :bot-blocked}
                    (try (scraper/run-pipeline {:database db :hostname host :api-key nil :fetcher nil} public-url)
                         (catch clojure.lang.ExceptionInfo e (ex-data e)))))
        (testing "the failed run is in the corpus: outcome set, content null, request-url recorded"
          (let [runs (kaleidoscope.persistence.rdbms/find-by-keys db :processing-runs {:hostname host})
                run  (first runs)]
            (is (match? {:outcome "bot-blocked" :content nil?} run))
            (is (match? {:request-url public-url :raw-html nil?}
                        (pipeline-db/get-raw-scrape db (:raw-scrape-id run) host)))))))))
```

Add the require for `kaleidoscope.persistence.rdbms` in the test ns (`[kaleidoscope.persistence.rdbms]`).

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test/run-pipeline-persists-success-test`
Expected: FAIL — `run-pipeline`/`extract` unresolved.

- [ ] **Step 3: Add `process`, `run-pipeline`, `extract`; delete the old `scrape` + `facts->result`**

In `src/kaleidoscope/api/recipe_scraper.clj`, add these requires:

```clojure
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
            [kaleidoscope.utils.versioning :as vu]
```

Delete the old `scrape` fn and the now-unused `facts->result` fn. Add the orchestrator in the "Entry point" section:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Orchestrator
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defn- ledger-entry
  "Fold one StageResult's provenance (technique + calls + warnings) into the
  accumulating ledger under `stage`."
  [ledger stage {:keys [technique llm-calls warnings]}]
  (-> ledger
      (assoc-in [:techniques stage] technique)
      (update :llm-calls into (or llm-calls []))
      (update :warnings  into (or warnings []))))

(defn- process
  "Run ACQUIRE -> PARSE -> NORMALIZE, short-circuiting at the first stage that
  returns an :outcome. Pure: no persistence; a :type :scrape failure is encoded
  as an :outcome (real bugs still throw). Returns {:artifacts :ledger}."
  [{:keys [api-key fetcher url]}]
  (let [empty-ledger {:techniques {} :llm-calls [] :warnings []}
        acq          (acquire {:fetcher fetcher :url url})]
    (if-let [outcome (:outcome acq)]
      {:artifacts {:raw (:raw acq)}
       :ledger    (assoc empty-ledger :outcome outcome :error-detail (:error-detail acq))}
      (let [raw (:artifact acq)
            l1  (ledger-entry empty-ledger :acquire acq)
            prs (parse {:api-key api-key :raw-html (:raw-html raw)})]
        (if-let [outcome (:outcome prs)]
          {:artifacts {:raw raw}
           :ledger    (assoc l1 :outcome outcome :error-detail (:error-detail prs))}
          (let [facts (:artifact prs)
                l2    (ledger-entry l1 :parse prs)
                nrm   (normalize {:api-key api-key :facts facts})]
            {:artifacts {:raw raw :facts facts :content (:artifact nrm)}
             :ledger    (assoc (ledger-entry l2 :normalize nrm) :outcome :success)}))))))

(defn- extraction-method
  "Derive the client-facing extraction-method from the recorded technique kinds."
  [{:keys [parse normalize]}]
  (cond
    (= parse :llm)              "llm"
    (= normalize :llm-grouping) "json-ld+llm-sections"
    :else                       "json-ld"))

(defn- build-scrape-result
  "Reconstruct the ScrapeResult view from the artifacts + ledger."
  [{:keys [content facts]} {:keys [techniques warnings]}]
  {:recipe            content
   :suggested-labels  (vec (:labels facts))
   :extraction-method (extraction-method techniques)
   :warnings          (vec warnings)})

(defn- ->scrape-failure
  [{:keys [outcome error-detail]}]
  (throw (ex-info (or (:message error-detail) "Scrape failed")
                  {:type :scrape :reason outcome})))

(defn extract
  "Run the pipeline WITHOUT persistence; return the ScrapeResult (no run-id) or
  throw {:type :scrape :reason ..}. For extraction where no DB is wired."
  [{:keys [api-key fetcher]} url]
  (let [{:keys [artifacts ledger]} (process {:api-key api-key :fetcher fetcher :url url})]
    (if (= :success (:outcome ledger))
      (build-scrape-result artifacts ledger)
      (->scrape-failure ledger))))

(defn run-pipeline
  "Acquire -> parse -> normalize over `url`; persist one raw_scrapes row and one
  processing_runs row on BOTH the success and short-circuited-failure paths;
  return the ScrapeResult augmented with :scrape-processing-run-id. On a
  :type :scrape failure the failed run is persisted, then the original ex-info is
  re-thrown so the handler's 422 mapping is unchanged."
  [{:keys [database hostname api-key fetcher]} url]
  (log/infof "Running recipe pipeline for %s" url)
  (let [{:keys [artifacts ledger]} (process {:api-key api-key :fetcher fetcher :url url})
        {raw-id :id} (pipeline-db/create-raw-scrape! database (assoc (:raw artifacts) :hostname hostname))
        {run-id :id} (pipeline-db/create-processing-run!
                      database
                      {:hostname         hostname
                       :raw-scrape-id    raw-id
                       :pipeline-version (:revision (vu/get-version-details))
                       :techniques       (:techniques ledger)
                       :facts            (:facts artifacts)
                       :content          (:content artifacts)
                       :llm-calls        (:llm-calls ledger)
                       :warnings         (:warnings ledger)
                       :outcome          (:outcome ledger)
                       :error-detail     (:error-detail ledger)})]
    (if (= :success (:outcome ledger))
      (assoc (build-scrape-result artifacts ledger) :scrape-processing-run-id run-id)
      (->scrape-failure ledger))))
```

- [ ] **Step 4: Run the full scraper test namespace**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`
Expected: PASS (all extraction tests via `extract`, plus the three `run-pipeline` persistence tests). If `process` fails to compile because `scrape`/`facts->result` still exist, confirm both were deleted.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "feat(scraper): run-pipeline orchestrator persists raw + provenance run

- process threads artifacts + folds a provenance ledger, short-circuits on failure
- run-pipeline persists raw_scrapes + processing_runs on success AND failure, stamps pipeline-version, returns run-id, re-throws scrape failures
- extract is the no-persistence convenience for extraction tests
- old scrape/facts->result removed

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: `api/recipes.clj` — persist `scrape-processing-run-id`

**Files:**
- Modify: `src/kaleidoscope/api/recipes.clj`
- Modify: `test/kaleidoscope/api/recipes_test.clj`

**Interfaces:**
- Consumes: `pipeline-db/create-raw-scrape!` + `pipeline-db/create-processing-run!` (test seeding only); the migration's `recipes.scrape_processing_run_id` column + FK.
- Produces: `create-recipe!` accepts `:scrape-processing-run-id` and writes it; `get-recipe` returns it (via `SELECT *`, already).

- [ ] **Step 1: Write a failing FK round-trip test**

In `test/kaleidoscope/api/recipes_test.clj`, add the require:

```clojure
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
```

Add:

```clojure
(deftest create-recipe-links-to-processing-run-test
  (let [db (embedded-pg/fresh-db!)
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      db {:hostname host :request-url "http://x/r"
                          :final-url "http://x/r" :http-status 200
                          :fetch-tier "direct" :raw-html "<html/>"})
        {run-id :id} (pipeline-db/create-processing-run!
                      db {:hostname host :raw-scrape-id raw-id :pipeline-version "v"
                          :techniques {:acquire :direct :parse :json-ld :normalize :single-section}
                          :content example-content :facts {:labels []}
                          :outcome :success})]
    (testing "a recipe created with a run-id persists the FK and returns it on read"
      (recipes/create-recipe! db (example-recipe :scrape-processing-run-id run-id))
      (is (match? {:scrape-processing-run-id run-id}
                  (recipes/get-recipe db host "chana-masala"))))
    (testing "a recipe created without a run-id has a nil link"
      (recipes/create-recipe! db (example-recipe :recipe-url "no-link"))
      (is (match? {:scrape-processing-run-id nil?}
                  (recipes/get-recipe db host "no-link"))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipes-test/create-recipe-links-to-processing-run-test`
Expected: FAIL — `create-recipe!` ignores `:scrape-processing-run-id`, so the read-back value is nil.

- [ ] **Step 3: Thread the field through `create-recipe!`**

In `src/kaleidoscope/api/recipes.clj`, add `scrape-processing-run-id` to the destructuring and the insert map of `create-recipe!`:

```clojure
(defn create-recipe!
  "Create a recipe. `:content` is the current recipe; `:original-content`, if
  given, is the immutable scrape (never modified after). `:scrape-processing-run-id`,
  if given, links to the pipeline run that produced the scrape. Accepts
  `:label-ids`; validates one-per-group before writing."
  [db {:keys [hostname recipe-url content original-content source-url author
              public-visibility label-ids scrape-processing-run-id] :as recipe}]
  (log/infof "Creating recipe %s for %s" recipe-url hostname)
  (next/with-transaction [tx db]
    (let [labels (validate-label-set! tx label-ids hostname)
          now    (utils/now)
          [{:keys [id] :as created}]
          (rdbms/insert! tx :recipes
                         {:id                       (utils/uuid)
                          :recipe-url               recipe-url
                          :hostname                 hostname
                          :content                  content
                          :original-content         original-content
                          :source-url               source-url
                          :author                   author
                          :public-visibility        (boolean public-visibility)
                          :scrape-processing-run-id scrape-processing-run-id
                          :created-at               now
                          :modified-at              now}
                         :ex-subtype :UnableToCreateRecipe)]
      (replace-label-assignments! tx id hostname labels)
      (get-recipe tx hostname recipe-url))))
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.recipes-test/create-recipe-links-to-processing-run-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipes.clj test/kaleidoscope/api/recipes_test.clj
git commit -m "feat(recipes): persist scrape-processing-run-id lineage link on create

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: `http_api/recipes.clj` — wire `run-pipeline` + run-id round-trip

**Files:**
- Modify: `src/kaleidoscope/http_api/recipes.clj`
- Modify: `test/kaleidoscope/http_api/recipes_test.clj`

**Interfaces:**
- Consumes: `scraper/run-pipeline` (Task 6); the `:database` component; `hu/get-host`.
- Produces: `/scrape` returns `ScrapeResult` incl. `:scrape-processing-run-id`; `POST /recipes` continues to thread `:scrape-processing-run-id` from the body (already merged via `(merge body {...})`).

- [ ] **Step 1: Update the `/scrape` mock + add an end-to-end round-trip test**

In `test/kaleidoscope/http_api/recipes_test.clj`, update `scrape-endpoint-test` to mock `run-pipeline` (not `scrape`) and include the run-id in the success mock's body:

```clojure
(deftest scrape-endpoint-test
  (let [app (make-app "custom-authenticated-user")]
    (testing "anonymous scrape is rejected"
      (is (match? {:status 401}
                  ((make-app "always-unauthenticated")
                   (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                       (mock/json-body {:url "http://example.com/r"}))))))

    (testing "a writer gets a draft back with a run-id (pipeline mocked to avoid network)"
      (with-redefs [scraper/run-pipeline (fn [_ _] {:recipe {:title "Mocked"
                                                             :sections [{:name nil :ingredients ["a"] :steps ["Mix"]}]}
                                                    :suggested-labels []
                                                    :extraction-method "json-ld"
                                                    :warnings []
                                                    :scrape-processing-run-id (random-uuid)})]
        (is (match? {:status 200 :body {:recipe {:title "Mocked"}
                                        :extraction-method "json-ld"
                                        :scrape-processing-run-id string?}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://example.com/r"})))))))

    (testing "a scrape failure surfaces as 422 with a reason"
      (with-redefs [scraper/run-pipeline (fn [_ _] (throw (ex-info "blocked" {:type :scrape :reason :blocked-url})))]
        (is (match? {:status 422 :body {:reason "blocked-url"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://169.254.169.254/"})))))))

    (testing "a rendering-fetcher failure is NOT a 422 — it propagates to the exception reporter (500)"
      (with-redefs [scraper/run-pipeline (fn [_ _] (throw (ex-info "firecrawl 500" {:type :scrape :reason :render-failed})))]
        (is (match? {:status 500}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                             as-writer
                             (mock/json-body {:url "http://example.com/r"})))))))))
```

Add an end-to-end lineage test that runs the real pipeline (fetch stubbed) so the run-id is a valid FK, then creates a recipe with it and reads it back:

```clojure
(deftest scrape-then-create-links-lineage-http-test
  (let [app (make-app "custom-authenticated-user")
        json-ld "<script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"name\":\"Chana Masala\",\"recipeIngredient\":[\"2 cups chickpeas\"],\"recipeInstructions\":\"Cook\"}</script>"]
    (with-redefs [scraper/fetch-direct (fn [_] {:raw-html json-ld :final-url "http://x/r" :http-status 200})]
      (let [{scrape-body :body} (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                                         as-writer
                                         (mock/json-body {:url "http://x/r"})))
            run-id (:scrape-processing-run-id scrape-body)]
        (testing "the scrape response carries a run-id"
          (is (string? run-id)))
        (testing "creating a recipe with the run-id persists the FK; it round-trips via GET"
          (app (-> (mock/request :post "https://andrewslai.com/recipes")
                   as-writer
                   (mock/json-body {:content {:title "Chana Masala"
                                              :sections [{:ingredients ["2 cups chickpeas"] :steps ["Cook"]}]}
                                    :public-visibility true
                                    :scrape-processing-run-id run-id})))
          (is (match? {:status 200 :body {:recipe-url "chana-masala"
                                          :scrape-processing-run-id run-id}}
                      (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test/scrape-then-create-links-lineage-http-test`
Expected: FAIL — the `/scrape` handler still calls `scraper/scrape` and doesn't pass the DB/hostname, so no run is persisted (run-id nil / FK violation).

- [ ] **Step 3: Update the `/scrape` handler to build the pipeline context and call `run-pipeline`**

In `src/kaleidoscope/http_api/recipes.clj`, replace the `/scrape` handler body:

```clojure
   ["/scrape"
    {:post {:summary    "Fetch + extract a recipe draft from a URL (persists the raw scrape + processing run)"
            :responses  (merge hu/openapi-401
                               {200 {:body models.recipes/ScrapeResult}
                                422 {:body [:map [:reason :string]]}})
            :parameters {:body [:map [:url :string]]}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (let [url (get-in parameters [:body :url])
                                ctx {:database (:database components)
                                     :hostname (hu/get-host request)
                                     :api-key  (:api-key (:workflow-executor components))
                                     :fetcher  (:recipe-fetcher components)}]
                            ;; Expected scrape outcomes become a 422 the client
                            ;; can act on. Anything else — including a Firecrawl
                            ;; :render-failed — propagates to the Bugsnag
                            ;; exception-reporter middleware, which reports it.
                            (try
                              (ok (scraper/run-pipeline ctx url))
                              (catch clojure.lang.ExceptionInfo e
                                (if (#{:fetch-failed :bot-blocked :no-recipe-found :blocked-url}
                                     (:reason (ex-data e)))
                                  (unprocessable-entity {:reason (name (:reason (ex-data e)))})
                                  (throw e))))))}}]
```

The `POST /recipes` handler already does `(merge body {...})`, so `:scrape-processing-run-id` flows through to `create-recipe!` unchanged — no edit needed there.

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test/scrape-endpoint-test`
Run: `./bin/test --focus kaleidoscope.http-api.recipes-test/scrape-then-create-links-lineage-http-test`
Expected: PASS both.

- [ ] **Step 5: Run the full suite**

Run: `task test`
Expected: PASS. If failures, use `task test:summary` for names and `./bin/test --focus <ns>` to isolate.

- [ ] **Step 6: Commit**

```bash
git add src/kaleidoscope/http_api/recipes.clj test/kaleidoscope/http_api/recipes_test.clj
git commit -m "feat(recipes): /scrape runs the persisting pipeline and returns a run-id

- /scrape handler builds pipeline context (db + hostname + api-key + fetcher), calls run-pipeline
- 422 mapping for expected scrape outcomes unchanged
- end-to-end test: scrape -> create-with-run-id -> GET round-trips the lineage link

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Frontend contract (out of this repo, documented so it isn't missed)

`kaleidoscope-ui` (separate repo): `POST /recipes/scrape` now returns `scrape-processing-run-id`; the UI must echo it in the subsequent `POST /recipes` body. Small, additive change.

## Out of scope (per DESIGN.md)

- No archive read/list API (retrieval via DB/psql for now).
- No re-processing runner (the table shape enables it; the batch re-run + technique-comparison tooling is later work).
- No retention/cleanup job.

## Self-Review notes

- **Spec coverage:** ACQUIRE/PARSE/NORMALIZE stages (T4–T5); orchestrator + ledger + short-circuit + persist-on-both-paths + pipeline-version (T6); `raw_scrapes`/`processing_runs`/link column (T1); `RawScrape`/`ExtractedFacts`/result+request+response schemas (T2); persistence layer (T3); `create-recipe!` link (T7); `/scrape` + `POST /` wiring + frontend note (T8). Testing matrix (persistence round-trip + composite FK, per-stage technique tags, pre-grouped LLM path with `llm_calls`, failure persists with correct outcome + no content, non-null `pipeline_version`, full request stored, run-id → run → raw HTML resolution, http run-id + create round-trip) is distributed across T3/T6/T8.
- **Type consistency:** `fetch-direct` → `{:raw-html :final-url :http-status}` (T4) consumed by `fetch-html`/`acquire`; `ExtractedFacts` keys (`:section-signals`, `:labels`, `:grouping`) defined T2, produced by `parse-json-ld`/`parse-with-llm` T5, consumed by `normalize` T5 + `build-scrape-result` T6; `create-raw-scrape!`/`create-processing-run!` signatures (T3) consumed verbatim by `run-pipeline` (T6); `:scrape-processing-run-id` keyword consistent across model (T2), api (T7), http (T8).
- **Decisions locked:** full RawScrape capture via the manual SSRF loop (final-url/status returned, not delegated to clj-http); LLM PARSE flattens to flat lists + a `:grouping` of index ranges so both techniques share NORMALIZE; grouping-failure flattening is tagged `:single-section` (an LLM call was still made and is recorded in `llm_calls`), which keeps `extraction-method` derivable from technique kinds and preserves every existing extraction-method assertion.
```
