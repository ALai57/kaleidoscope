# Recipe Photo Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a writer create a recipe by uploading photo(s) of an offline source (cookbook page or screenshot), transcribed and structured into a review draft exactly like the existing URL-scrape flow.

**Architecture:** Decomplect OCR ("images → text") from interpretation ("text → recipe"). OCR sits behind a new `ImageTranscriber` protocol (Claude vision now; Google Cloud Vision committed next). The transcript feeds the *existing* text-to-facts LLM interpretation, then the existing `NORMALIZE` + provenance stack. Acquisition (`acquire-url` / `acquire-photo`) produces a source-agnostic `RawSource` value that a single `run-pipeline` consumes.

**Tech Stack:** Clojure, reitit, next.jdbc + HoneySQL, Malli, Migratus, Kaocha + matcher-combinators, embedded-h2 for tests, Anthropic Messages API (vision).

## Global Constraints

- 3-layer separation: `http_api/` → `api/` → `persistence/`. No persistence calls from `http_api/`; no HTTP in `api/`. (copied from CLAUDE.md)
- `!` suffix on side-effecting fns; SQL `snake_case`, Clojure `kebab-case` (auto via camel-snake-kebab).
- Every schema change is a new numbered `resources/migrations/*.up.sql` / `.down.sql` pair. Migrations run on **both** embedded-H2 (tests) and Postgres — use portable DDL.
- Recipe scraper persistence tests run on **embedded-h2** (`embedded-h2/fresh-db!`). The default vision model is `claude-haiku-4-5`.
- Never pipe full test output; use `task test:summary` or `--focus` (CLAUDE.md "Debugging tests").
- Reuse shared fns; do not fork parallel variants.

---

### Task 1: Honest corpus column names (`raw_content` + `source_kind`)

Rename the misnamed `raw_html` column to `raw_content`, add a `source_kind` discriminator, and relax `request_url` to nullable — threaded through persistence, the model, and the one existing scraper call site. URL behavior stays identical; all tests stay green.

**Files:**
- Create: `resources/migrations/20260712000002-add-photo-source-kind.up.sql`
- Create: `resources/migrations/20260712000002-add-photo-source-kind.down.sql`
- Modify: `src/kaleidoscope/persistence/scrape_pipeline.clj` (`create-raw-scrape!`)
- Modify: `src/kaleidoscope/models/recipes.cljc` (`RawScrape`)
- Modify: `src/kaleidoscope/api/recipe_scraper.clj` (`run-pipeline` create-raw-scrape! call site)
- Test: `test/kaleidoscope/persistence/scrape_pipeline_test.clj`

**Interfaces:**
- Produces: `create-raw-scrape!` now consumes `{:hostname :source-kind :request-url :final-url :http-status :fetch-tier :raw-content}` (was `:raw-html`, no `:source-kind`). `get-raw-scrape` returns `:raw-content` + `:source-kind`.

- [ ] **Step 1: Write the migration (up)**

Create `resources/migrations/20260712000002-add-photo-source-kind.up.sql`:

```sql
-- Photo import: the corpus is no longer HTML-only. Rename raw_html -> raw_content,
-- add a source_kind discriminator, and allow a null request_url (photos have none).
-- See plans/2026-07-12-recipe-photo-import/DESIGN.md.
ALTER TABLE raw_scrapes RENAME COLUMN raw_html TO raw_content;
--;;
ALTER TABLE raw_scrapes ADD COLUMN source_kind VARCHAR;
--;;
UPDATE raw_scrapes SET source_kind = 'url';
--;;
ALTER TABLE raw_scrapes ALTER COLUMN source_kind SET NOT NULL;
--;;
ALTER TABLE raw_scrapes ALTER COLUMN request_url DROP NOT NULL;
```

- [ ] **Step 2: Write the migration (down)**

Create `resources/migrations/20260712000002-add-photo-source-kind.down.sql`:

```sql
ALTER TABLE raw_scrapes ALTER COLUMN request_url SET NOT NULL;
--;;
ALTER TABLE raw_scrapes DROP COLUMN source_kind;
--;;
ALTER TABLE raw_scrapes RENAME COLUMN raw_content TO raw_html;
```

- [ ] **Step 3: Update the failing persistence test to the new shape**

In `test/kaleidoscope/persistence/scrape_pipeline_test.clj`, update `seed-raw!` and every `:raw-html` reference to `:raw-content`, and add `:source-kind`:

```clojure
(defn- seed-raw! [db & {:as overrides}]
  (pipeline-db/create-raw-scrape!
   db (merge {:hostname host :source-kind "url" :request-url "http://example.com/r"
              :final-url "http://example.com/r" :http-status 200
              :fetch-tier "direct" :raw-content "<html>recipe</html>"}
             overrides)))
```

Then replace the three assertion/seed sites that still say `:raw-html`:
- `raw-scrape-round-trip-test`: `(seed-raw! db :raw-html big)` → `(seed-raw! db :raw-content big)`; assertion `:raw-html big` → `:raw-content big`.
- `pre-fetch-failure-raw-scrape-test`: add `:source-kind "url"` to the create map; assertion `:raw-html nil?` → `:raw-content nil?`.
- `non-ascii-round-trip-test`: `(seed-raw! db :raw-html text)` → `(seed-raw! db :raw-content text)`; assertion `{:raw-html text}` → `{:raw-content text}`.

- [ ] **Step 4: Run the persistence test to verify it fails**

Run: `./bin/test --focus kaleidoscope.persistence.scrape-pipeline-test 2>&1 | grep -E "^(FAIL|ERROR) in|Syntax error|failed to execute"`
Expected: FAIL/ERROR — `create-raw-scrape!` doesn't accept `:source-kind`/`:raw-content` yet (column `raw_content` unknown until migration + code land together).

- [ ] **Step 5: Update `create-raw-scrape!`**

In `src/kaleidoscope/persistence/scrape_pipeline.clj`:

```clojure
(defn create-raw-scrape!
  "Insert one immutable raw_scrapes row; return the created row (incl. :id).
  `source-kind` is 'url' | 'photo'. Fetch fields are optional — a pre-fetch
  failure (url) or a photo import records only what it has."
  [db {:keys [hostname source-kind request-url final-url http-status fetch-tier raw-content]}]
  (first (rdbms/insert! db :raw-scrapes
                        {:id          (utils/uuid)
                         :hostname    hostname
                         :source-kind source-kind
                         :request-url request-url
                         :final-url   final-url
                         :http-status http-status
                         :fetch-tier  fetch-tier
                         :raw-content raw-content
                         :created-at  (utils/now)}
                        :ex-subtype :UnableToCreateRawScrape)))
```

- [ ] **Step 6: Update the `RawScrape` model**

In `src/kaleidoscope/models/recipes.cljc`, replace the `RawScrape` def:

```clojure
(def RawScrape
  ;; Validated before persistence. Fetch fields are nullable so a pre-fetch
  ;; failure (url) or a photo import still records source-kind + hostname.
  [:map
   [:hostname    :string]
   [:source-kind [:enum "url" "photo"]]
   [:request-url {:optional true} [:maybe :string]]
   [:final-url   {:optional true} [:maybe :string]]
   [:http-status {:optional true} [:maybe :int]]
   [:fetch-tier  {:optional true} [:maybe :string]]
   [:raw-content {:optional true} [:maybe :string]]])
```

- [ ] **Step 7: Update the one scraper call site so URL scrapes still persist**

In `src/kaleidoscope/api/recipe_scraper.clj`, the `run-pipeline` fn currently calls
`(pipeline-db/create-raw-scrape! database (assoc (:raw artifacts) :hostname hostname))`.
The acquire artifact still carries `:raw-html` at this point (changed in Task 3), so translate it here:

```clojure
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      database (-> (:raw artifacts)
                                   (assoc :hostname hostname :source-kind "url")
                                   (clojure.set/rename-keys {:raw-html :raw-content})))
```

Add `[clojure.set :as set]` is unnecessary — `clojure.set/rename-keys` is fully qualified above; confirm `clojure.set` is required or use the fully-qualified symbol (it resolves without an alias only if required). Add to the `ns` `:require`: `[clojure.set]`.

- [ ] **Step 8: Run persistence + scraper tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.persistence.scrape-pipeline-test --focus kaleidoscope.api.recipe-scraper-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran|tests"`
Expected: 0 failures. (`recipe-scraper-test`'s `get-raw-scrape` assertions still say `:raw-html`; fix them now: in `run-pipeline-persists-success-test` `{:raw-html json-ld-html}` → `{:raw-content json-ld-html}`, and in `run-pipeline-persists-failure-and-rethrows-test` `{:request-url public-url :raw-html nil?}` → `{:request-url public-url :raw-content nil?}`.) Re-run until green.

- [ ] **Step 9: Commit**

```bash
git add resources/migrations/20260712000002-* src/kaleidoscope/persistence/scrape_pipeline.clj src/kaleidoscope/models/recipes.cljc src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/persistence/scrape_pipeline_test.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "$(cat <<'EOF'
refactor(scrape): rename raw_html->raw_content, add source_kind discriminator

- Migration renames the corpus column and adds source_kind ('url'|'photo')
- request_url relaxed to nullable (photos have no URL)
- create-raw-scrape! + RawScrape model updated; URL path unchanged

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 2: Extract shared `parse-text` interpretation

Split the LLM interpretation out of `parse-with-llm` so the same "text → ExtractedFacts" step serves both the URL html path and the photo transcript path. Pure refactor; URL behavior unchanged.

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj` (`parse-with-llm` → `parse-text` + thin wrapper)
- Test: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Produces: `parse-text [api-key source-text] -> {:artifact ExtractedFacts :technique :llm :llm-calls [...] :warnings [...]}` (private). `parse-with-llm [api-key html]` remains as `(parse-text api-key (html->text html))`.

- [ ] **Step 1: Write the failing test**

Add to `test/kaleidoscope/api/recipe_scraper_test.clj`:

```clojure
(deftest parse-text-interprets-plain-text-test
  (testing "parse-text turns already-plain text into flat facts + grouping via the LLM"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\",\"beef\"],\"steps\":[\"Simmer\"]}],\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:technique :llm
                   :artifact  {:title "Stew" :ingredients ["carrots" "beef"] :steps ["Simmer"]
                               :grouping [{:name nil? :ingredients [0 1] :steps [0]}]
                               :labels ["comfort"]}
                   :llm-calls [{:purpose :parse :model "claude-haiku-4-5"}]}
                  (#'scraper/parse-text "sk-test" "Grandma's stew: carrots, beef. Simmer 2 hours."))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test 2>&1 | grep -E "^(FAIL|ERROR) in|Unable to resolve"`
Expected: ERROR — `parse-text` unresolved.

- [ ] **Step 3: Refactor `parse-with-llm` into `parse-text` + wrapper**

In `src/kaleidoscope/api/recipe_scraper.clj`, replace the `parse-with-llm` defn with:

```clojure
(defn- parse-text
  "Interpret already-plain source text into ExtractedFacts via the LLM. Shared by
  the URL html path (over `html->text`) and the photo transcript path. Asks for
  sections, then flattens to flat ingredient/step lists + a :grouping of index
  ranges NORMALIZE merges deterministically."
  [api-key source-text]
  (let [text     (subs source-text 0 (min 50000 (count source-text)))
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

(defn- parse-with-llm
  "URL html path: strip to text, then interpret. Thin wrapper over `parse-text`."
  [api-key html]
  (parse-text api-key (html->text html)))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: 0 failures (new test passes; all existing LLM-path tests unchanged).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "$(cat <<'EOF'
refactor(scrape): extract shared parse-text interpretation from parse-with-llm

- parse-text: plain text -> ExtractedFacts, reusable by url + photo paths
- parse-with-llm becomes parse-text over html->text (behavior unchanged)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 3: `RawSource` value; decomplect acquisition; techniques map

Reshape acquisition into a source-agnostic `RawSource` value. Rename `acquire` → `acquire-url`, make `parse`/`process`/`run-pipeline` consume a `RawSource`, add the `scrape-url` entry point, and replace the flattened `extraction-method` string with the `techniques` map.

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj` (`acquire-url`, `parse`, `process`, `run-pipeline`, `scrape-url`, `extract`, drop `extraction-method`, `build-scrape-result`)
- Modify: `src/kaleidoscope/models/recipes.cljc` (`ScrapeResult`)
- Modify: `src/kaleidoscope/http_api/recipes.clj` (`/recipes/scrape` handler calls `scrape-url`)
- Test: `test/kaleidoscope/api/recipe_scraper_test.clj`, `test/kaleidoscope/http_api/recipes_test.clj`

**Interfaces:**
- Produces:
  - `RawSource` = `{:source-kind :url|:photo :raw-content string :acquire-technique keyword :llm-calls [..] (url only: :request-url :final-url :http-status :fetch-tier)}` or a short-circuit `{:source-kind :url :outcome kw :error-detail {..} :request-url url}`.
  - `acquire-url [{:keys [fetcher url]}] -> RawSource`
  - `parse [{:keys [api-key raw-source]}] -> StageResult` (dispatches on `:source-kind`)
  - `run-pipeline [{:keys [database hostname api-key]} raw-source] -> ScrapeResult+run-id` (persists; throws on failure)
  - `scrape-url [{:keys [database hostname api-key fetcher]} url] -> ScrapeResult+run-id`
  - `ScrapeResult` now carries `:techniques {:acquire :parse :normalize}` (keywords) instead of `:extraction-method`.

- [ ] **Step 1: Update the failing tests to the RawSource + techniques shape**

In `test/kaleidoscope/api/recipe_scraper_test.clj`:

Replace `acquire-produces-raw-scrape-artifact-test`:

```clojure
(deftest acquire-url-produces-raw-source-test
  (testing "a successful direct fetch yields a :url RawSource with raw-content"
    (with-redefs [scraper/fetch-direct (fn [_] (direct json-ld-html))]
      (is (match? {:source-kind :url :request-url public-url :fetch-tier "direct"
                   :http-status 200 :raw-content json-ld-html :acquire-technique :direct}
                  (scraper/acquire-url {:fetcher nil :url public-url})))))
  (testing "an SSRF block yields a short-circuit RawSource (outcome + request-url)"
    (with-redefs [scraper/fetch-direct (fn [_] (throw (ex-info "blocked" {:type :scrape :reason :blocked-url})))]
      (is (match? {:source-kind :url :outcome :blocked-url
                   :error-detail {:reason :blocked-url}
                   :request-url "http://169.254.169.254/"}
                  (scraper/acquire-url {:fetcher nil :url "http://169.254.169.254/"}))))))
```

Replace the two `scraper/parse {:api-key .. :raw-html ..}` call forms in `parse-stage-json-ld-then-llm-test` with the RawSource form:

```clojure
    (is (match? {:technique :json-ld :artifact {:title "Chana Masala"}}
                (scraper/parse {:api-key "sk-test" :raw-source {:source-kind :url :raw-content json-ld-html}})))
    (is (match? {:outcome :no-recipe-found}
                (scraper/parse {:api-key nil :raw-source {:source-kind :url :raw-content "<html>no structured data</html>"}})))
    ;; and the api-key llm branch:
                  (scraper/parse {:api-key "sk-test" :raw-source {:source-kind :url :raw-content "<html>stew</html>"}})
```

Update every `:extraction-method "X"` assertion to a `:techniques` submap (matcher-combinators matches map subsets):

| Test | old | new |
|---|---|---|
| `unsectioned-scrape-assembles-single-section-test` | `:extraction-method "json-ld"` | `:techniques {:parse :json-ld :normalize :single-section}` |
| `llm-fallback-invoked-test` | `:extraction-method "llm"` | `:techniques {:parse :llm}` |
| `sectioned-scrape-groups-with-llm-test` | `:extraction-method "json-ld+llm-sections"` | `:techniques {:parse :json-ld :normalize :llm-grouping}` |
| `invalid-grouping-falls-back-test` | `:extraction-method "json-ld"` | `:techniques {:parse :json-ld :normalize :single-section}` |
| `header-ingredient-lines-trigger-grouping-test` | `:extraction-method "json-ld+llm-sections"` | `:techniques {:parse :json-ld :normalize :llm-grouping}` |
| `sectioned-without-api-key-flattens-with-warning-test` | `:extraction-method "json-ld"` | `:techniques {:parse :json-ld :normalize :single-section}` |
| `dropped-header-lines-surface-as-warning-test` | `:extraction-method "json-ld+llm-sections"` | `:techniques {:parse :json-ld :normalize :llm-grouping}` |
| `bot-blocked-falls-back-to-fetcher-test` | `:extraction-method "json-ld"` | `:techniques {:parse :json-ld}` |

Update the three orchestrator tests to call `scrape-url` (was `run-pipeline`) and the result techniques:
- `run-pipeline-persists-success-test`: `(scraper/run-pipeline {...} public-url)` → `(scraper/scrape-url {...} public-url)`; result assertion `:extraction-method "json-ld"` → `:techniques {:parse :json-ld}`. The stored-run assertion `:techniques {:acquire "direct" :parse "json-ld" :normalize "single-section"}` stays.
- `run-pipeline-records-llm-calls-test`: `run-pipeline` → `scrape-url`.
- `run-pipeline-persists-failure-and-rethrows-test`: `run-pipeline` → `scrape-url`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test 2>&1 | grep -E "^(FAIL|ERROR) in|Unable to resolve"`
Expected: failures — `acquire-url` / `scrape-url` unresolved, `:techniques` absent.

- [ ] **Step 3: Rewrite `acquire-url`, `parse`, `process`, `build-scrape-result`, `run-pipeline`, `scrape-url`, `extract`**

In `src/kaleidoscope/api/recipe_scraper.clj`, replace the `acquire` fn with `acquire-url`:

```clojure
(defn acquire-url
  "ACQUIRE (url): fetch the page (SSRF-guarded), direct then rendering fallback,
  into a :url RawSource. On a :type :scrape failure returns a short-circuit
  RawSource {:outcome reason :error-detail {..} :request-url url}. Other
  exceptions propagate."
  [{:keys [fetcher url]}]
  (try
    (let [{:keys [raw-html final-url http-status fetch-tier]} (fetch-html fetcher url)]
      {:source-kind       :url
       :raw-content       raw-html
       :request-url       url
       :final-url         final-url
       :http-status       http-status
       :fetch-tier        fetch-tier
       :acquire-technique (keyword fetch-tier)
       :llm-calls         []})
    (catch clojure.lang.ExceptionInfo e
      (let [{:keys [type reason]} (ex-data e)]
        (if (= :scrape type)
          {:source-kind  :url
           :outcome      reason
           :error-detail {:message (ex-message e) :reason reason}
           :request-url  url}
          (throw e))))))
```

Replace the `parse` fn:

```clojure
(defn parse
  "PARSE: RawSource -> ExtractedFacts StageResult. :url tries JSON-LD then
  LLM-over-html-text; :photo interprets the transcript directly via `parse-text`.
  :no-recipe-found when neither yields facts (verdict lives here for both sources)."
  [{:keys [api-key raw-source]}]
  (let [{:keys [source-kind raw-content]} raw-source]
    (case source-kind
      :url   (if-let [facts (parse-json-ld raw-content)]
               {:artifact facts :technique :json-ld}
               (if api-key
                 (parse-with-llm api-key raw-content)
                 {:outcome      :no-recipe-found
                  :error-detail {:message "No recipe found and no LLM available"
                                 :reason  :no-recipe-found}}))
      :photo (if (and api-key (not (clojure.string/blank? raw-content)))
               (parse-text api-key raw-content)
               {:outcome      :no-recipe-found
                :error-detail {:message "No recipe text found in images"
                               :reason  :no-recipe-found}}))))
```

(`clojure.string` is already required as `str` — use `(str/blank? raw-content)`.)

Replace `process` so it consumes a `RawSource` (no acquire inside):

```clojure
(defn- process
  "Run PARSE -> NORMALIZE over an already-acquired RawSource, short-circuiting on
  the RawSource's own :outcome or PARSE's :outcome. Pure: no persistence. The
  acquire technique is folded from the RawSource. Returns {:artifacts :ledger}."
  [{:keys [api-key raw-source]}]
  (let [empty-ledger {:techniques {} :llm-calls (vec (:llm-calls raw-source)) :warnings []}
        raw          (select-keys raw-source [:source-kind :request-url :final-url
                                              :http-status :fetch-tier :raw-content])
        l0           (assoc-in empty-ledger [:techniques :acquire] (:acquire-technique raw-source))]
    (if-let [outcome (:outcome raw-source)]
      {:artifacts {:raw (select-keys raw-source [:source-kind :request-url])}
       :ledger    (assoc empty-ledger :outcome outcome :error-detail (:error-detail raw-source))}
      (let [prs (parse {:api-key api-key :raw-source raw-source})]
        (if-let [outcome (:outcome prs)]
          {:artifacts {:raw raw}
           :ledger    (assoc (ledger-entry l0 :parse prs) :outcome outcome :error-detail (:error-detail prs))}
          (let [facts (:artifact prs)
                l2    (ledger-entry l0 :parse prs)
                nrm   (normalize {:api-key api-key :facts facts})]
            {:artifacts {:raw raw :facts facts :content (:artifact nrm)}
             :ledger    (assoc (ledger-entry l2 :normalize nrm) :outcome :success)}))))))
```

Replace `build-scrape-result` and delete `extraction-method`:

```clojure
(defn- build-scrape-result
  "Reconstruct the ScrapeResult view. Surfaces the decomplected `techniques` map
  (acquire × parse × normalize) rather than a flattened method string."
  [{:keys [content facts]} {:keys [techniques warnings]}]
  {:recipe           content
   :suggested-labels (vec (:labels facts))
   :techniques       techniques
   :warnings         (vec warnings)})
```

Replace `run-pipeline` to take a `RawSource`, and add `scrape-url`:

```clojure
(defn run-pipeline
  "Process + persist a RawSource (from any acquirer): one raw_scrapes row + one
  processing_runs row on BOTH success and short-circuit-failure; returns the
  ScrapeResult augmented with :scrape-processing-run-id, or re-throws
  {:type :scrape :reason ..} on failure. The single, source-agnostic pipeline."
  [{:keys [database hostname api-key]} raw-source]
  (let [{:keys [artifacts ledger]} (process {:api-key api-key :raw-source raw-source})
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      database (-> (:raw artifacts)
                                   (assoc :hostname hostname)
                                   (update :source-kind name)))
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

(defn scrape-url
  "URL entry point: acquire the page, then run the one pipeline."
  [{:keys [fetcher] :as ctx} url]
  (log/infof "Running recipe pipeline for %s" url)
  (run-pipeline ctx (acquire-url {:fetcher fetcher :url url})))
```

Replace `extract` to acquire then process:

```clojure
(defn extract
  "Run the pipeline WITHOUT persistence; return the ScrapeResult (no run-id) or
  throw {:type :scrape :reason ..}. For extraction where no DB is wired."
  [{:keys [api-key fetcher]} url]
  (let [{:keys [artifacts ledger]} (process {:api-key   api-key
                                             :raw-source (acquire-url {:fetcher fetcher :url url})})]
    (if (= :success (:outcome ledger))
      (build-scrape-result artifacts ledger)
      (->scrape-failure ledger))))
```

(The Task 1 `clojure.set/rename-keys` translation in `run-pipeline` is now removed — `acquire-url` already produces `:raw-content` and `:source-kind`. Drop the `[clojure.set]` require added in Task 1 if now unused.)

- [ ] **Step 4: Update the `ScrapeResult` model**

In `src/kaleidoscope/models/recipes.cljc`:

```clojure
(def ScrapeResult
  [:map
   [:recipe           RecipeContent]
   [:suggested-labels [:sequential :string]]
   [:techniques       [:map
                       [:acquire   :keyword]
                       [:parse     :keyword]
                       [:normalize :keyword]]]
   [:warnings         [:sequential :string]]
   [:scrape-processing-run-id :uuid]])
```

- [ ] **Step 5: Point the `/recipes/scrape` handler at `scrape-url`**

In `src/kaleidoscope/http_api/recipes.clj`, in the `/scrape` handler, change `(scraper/run-pipeline ctx url)` to `(scraper/scrape-url ctx url)`.

- [ ] **Step 6: Update the HTTP scrape test to the new entry point + techniques**

In `test/kaleidoscope/http_api/recipes_test.clj`, `scrape-endpoint-test`: redef `scraper/scrape-url` (was `scraper/run-pipeline`) in all three `with-redefs`; change the mocked success return's `:extraction-method "json-ld"` to `:techniques {:acquire :direct :parse :json-ld :normalize :single-section}` and the assertion `:extraction-method "json-ld"` to `:techniques {:parse "json-ld"}` (JSON-decoded values are strings). `scrape-then-create-links-lineage-http-test` needs no entry-point change (it redefs `fetch-direct`).

- [ ] **Step 7: Run scraper + http recipe tests**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test --focus kaleidoscope.http-api.recipes-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: 0 failures.

- [ ] **Step 8: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj src/kaleidoscope/models/recipes.cljc src/kaleidoscope/http_api/recipes.clj test/kaleidoscope/api/recipe_scraper_test.clj test/kaleidoscope/http_api/recipes_test.clj
git commit -m "$(cat <<'EOF'
refactor(scrape): decomplect acquisition into a source-agnostic RawSource

- acquire-url produces a RawSource value; one run-pipeline consumes it
- parse dispatches on :source-kind; scrape-url is the URL entry point
- ScrapeResult surfaces the techniques map, dropping the flattened
  extraction-method string

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 4: `ImageTranscriber` protocol (Claude vision + mock + Google stub)

The OCR seam: images → text. Claude vision is the default; a Google Cloud Vision record is wired as the committed second impl (throws until built); a mock returns a canned transcript.

**Files:**
- Create: `src/kaleidoscope/api/image_transcriber.clj`
- Test: `test/kaleidoscope/api/image_transcriber_test.clj`

**Interfaces:**
- Produces:
  - `(defprotocol ImageTranscriber (transcribe [this images]))` — `images: [{:content-type string :bytes byte-array}]` → `{:transcript string :technique keyword :llm-calls [{:purpose :model :request :response}]}`.
  - `make-claude-vision-transcriber [{:keys [api-key model]}]`
  - `make-google-vision-transcriber [{:keys [api-key]}]`
  - `make-mock-transcriber ([]) ([transcript])`

- [ ] **Step 1: Write the failing test**

Create `test/kaleidoscope/api/image_transcriber_test.clj`:

```clojure
(ns kaleidoscope.api.image-transcriber-test
  (:require [kaleidoscope.api.image-transcriber :as transcriber]
            [kaleidoscope.workflows.llm-executor :as llm]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(defn- img [s] {:content-type "image/jpeg" :bytes (.getBytes ^String s "UTF-8")})

(deftest claude-vision-transcribe-test
  (testing "one image block per image + a trailing text block; transcript returned verbatim"
    (let [captured (atom nil)]
      (with-redefs [llm/post-anthropic-sync
                    (fn [_ req] (reset! captured req)
                      {:content [{:text "Chana Masala\n2 cups chickpeas\nCook."}]})]
        (let [result (transcriber/transcribe
                      (transcriber/make-claude-vision-transcriber {:api-key "sk-test"})
                      [(img "page1") (img "page2")])]
          (is (match? {:transcript "Chana Masala\n2 cups chickpeas\nCook."
                       :technique  :claude-vision
                       :llm-calls  [{:purpose :transcribe :model "claude-haiku-4-5"}]}
                      result))
          (is (match? {:messages [{:content [{:type "image"} {:type "image"} {:type "text"}]}]}
                      @captured)))))))

(deftest mock-transcriber-returns-canned-text-test
  (is (match? {:transcript "hi" :technique :claude-vision}
              (transcriber/transcribe (transcriber/make-mock-transcriber "hi") [(img "x")]))))

(deftest google-vision-not-yet-implemented-test
  (is (thrown? UnsupportedOperationException
               (transcriber/transcribe (transcriber/make-google-vision-transcriber {:api-key "k"}) [(img "x")]))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.image-transcriber-test 2>&1 | grep -E "^(FAIL|ERROR) in|could not|No namespace"`
Expected: ERROR — namespace `kaleidoscope.api.image-transcriber` not found.

- [ ] **Step 3: Implement the transcriber**

Create `src/kaleidoscope/api/image_transcriber.clj`:

```clojure
(ns kaleidoscope.api.image-transcriber
  "Transcribe uploaded recipe photos into plain text. OCR only — it does not
  decide whether the text is a recipe or impose structure; interpretation is the
  scraper's shared PARSE (`parse-text`). One protocol, two impls on the OCR-quality
  axis: Claude vision (default) and Google Cloud Vision (committed follow-up). No
  image bytes are retained. See plans/2026-07-12-recipe-photo-import/DESIGN.md."
  (:require [kaleidoscope.workflows.llm-executor :as llm]
            [taoensso.timbre :as log])
  (:import (java.util Base64)))

(defprotocol ImageTranscriber
  (transcribe [this images]
    "images: [{:content-type string :bytes byte-array}] ->
    {:transcript string :technique keyword :llm-calls [{:purpose :model :request :response}]}.
    The transcript may be empty; the interpretation stage renders the no-recipe verdict."))

(def ^:private transcribe-model "claude-haiku-4-5")

(def ^:private transcribe-prompt
  "You are given one or more images of a single recipe (a cookbook page or a screenshot). Transcribe ALL text you can read, verbatim, in natural reading order, concatenating multiple images in the order given. Do not interpret, summarize, translate, reformat, or add commentary. Preserve ingredient lines and step text exactly. Output only the transcribed text.")

(defn- ->image-block
  [{:keys [content-type bytes]}]
  {:type   "image"
   :source {:type       "base64"
            :media_type content-type
            :data       (.encodeToString (Base64/getEncoder) bytes)}})

(defrecord ClaudeVisionTranscriber [api-key model]
  ImageTranscriber
  (transcribe [_ images]
    (log/infof "Transcribing %d image(s) via Claude vision" (count images))
    (let [model'   (or model transcribe-model)
          request  {:model      model'
                    :max_tokens 4096
                    :system     transcribe-prompt
                    :messages   [{:role    "user"
                                  :content (conj (mapv ->image-block images)
                                                 {:type "text" :text "Transcribe the recipe in these images."})}]}
          response (llm/post-anthropic-sync api-key request)
          text     (-> response :content first :text)]
      {:transcript (or text "")
       :technique  :claude-vision
       :llm-calls  [{:purpose :transcribe :model model' :request request :response response}]})))

;; Committed second implementation (handwriting / dense layouts). Wired in
;; init.env so the axis of variation is real; the GCP call is a follow-up.
(defrecord GoogleVisionTranscriber [api-key]
  ImageTranscriber
  (transcribe [_ _images]
    (throw (UnsupportedOperationException. "google-vision transcriber not yet implemented"))))

;; Local dev / tests without ANTHROPIC_API_KEY: canned transcript.
(defrecord MockTranscriber [transcript]
  ImageTranscriber
  (transcribe [_ _images]
    {:transcript transcript :technique :claude-vision :llm-calls []}))

(defn make-claude-vision-transcriber [{:keys [api-key model]}]
  (->ClaudeVisionTranscriber api-key model))

(defn make-google-vision-transcriber [{:keys [api-key]}]
  (->GoogleVisionTranscriber api-key))

(defn make-mock-transcriber
  ([] (make-mock-transcriber "Chana Masala\n\nIngredients:\n2 cups chickpeas\n1 tbsp flour\n\nSteps:\nSoak overnight.\nCook until tender."))
  ([transcript] (->MockTranscriber transcript)))
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.image-transcriber-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/image_transcriber.clj test/kaleidoscope/api/image_transcriber_test.clj
git commit -m "$(cat <<'EOF'
feat(recipes): add ImageTranscriber protocol with Claude-vision OCR

- transcribe: images -> verbatim text (OCR only, no interpretation)
- Claude vision default; Google Cloud Vision stub as committed 2nd impl
- MockTranscriber for local dev / tests

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 5: `acquire-photo` + `scrape-photo` pipeline path

Wire the transcriber into a `:photo` `RawSource` and add the photo entry point that feeds the one `run-pipeline`.

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj` (require transcriber; add `acquire-photo`, `scrape-photo`)
- Test: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Consumes: `transcriber/transcribe` (Task 4); `run-pipeline`, `parse` (Task 3).
- Produces:
  - `acquire-photo [{:keys [transcriber images]}] -> :photo RawSource`
  - `scrape-photo [{:keys [database hostname api-key transcriber]} images] -> ScrapeResult+run-id`

- [ ] **Step 1: Write the failing tests**

Add to `test/kaleidoscope/api/recipe_scraper_test.clj` (add `[kaleidoscope.api.image-transcriber :as transcriber]` to the ns `:require`):

```clojure
(defn- stub-transcriber [text]
  (reify transcriber/ImageTranscriber
    (transcribe [_ _images]
      {:transcript text :technique :claude-vision
       :llm-calls  [{:purpose :transcribe :model "claude-haiku-4-5" :request {} :response {}}]})))

(def ^:private one-image [{:content-type "image/jpeg" :bytes (.getBytes "img")}])

(deftest photo-pipeline-persists-success-test
  (testing "a photo import transcribes -> interprets -> persists raw(photo) + run"
    (let [db (embedded-h2/fresh-db!)]
      (with-redefs [llm/post-anthropic-sync
                    (fn [_ _] {:content [{:text "{\"title\":\"Chana Masala\",\"sections\":[{\"name\":null,\"ingredients\":[\"2 cups chickpeas\"],\"steps\":[\"Cook\"]}],\"suggested_labels\":[\"indian\"]}"}]})]
        (let [{:keys [scrape-processing-run-id] :as result}
              (scraper/scrape-photo {:database db :hostname host :api-key "sk-test"
                                     :transcriber (stub-transcriber "Chana Masala\n2 cups chickpeas\nCook.")}
                                    one-image)]
          (is (match? {:recipe {:title "Chana Masala"}
                       :techniques {:acquire :claude-vision :parse :llm}
                       :scrape-processing-run-id uuid?}
                      result))
          (let [run (pipeline-db/get-processing-run db scrape-processing-run-id host)]
            (is (match? {:techniques {:acquire "claude-vision" :parse "llm"}
                         :content {:title "Chana Masala"} :outcome "success"}
                        run))
            (is (match? {:source-kind "photo" :raw-content "Chana Masala\n2 cups chickpeas\nCook."
                         :fetch-tier nil? :request-url nil?}
                        (pipeline-db/get-raw-scrape db (:raw-scrape-id run) host)))))))))

(deftest photo-pipeline-empty-transcript-fails-test
  (testing "an empty transcript is :no-recipe-found (verdict in interpretation) + persisted failed run"
    (let [db (embedded-h2/fresh-db!)]
      (is (match? {:reason :no-recipe-found}
                  (try (scraper/scrape-photo {:database db :hostname host :api-key "sk-test"
                                              :transcriber (stub-transcriber "")}
                                             one-image)
                       (catch clojure.lang.ExceptionInfo e (ex-data e)))))
      (let [run (first (kaleidoscope.persistence.rdbms/find-by-keys db :processing-runs {:hostname host}))]
        (is (match? {:outcome "no-recipe-found" :content nil?} run))
        (is (match? {:source-kind "photo" :raw-content ""}
                    (pipeline-db/get-raw-scrape db (:raw-scrape-id run) host)))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test 2>&1 | grep -E "^(FAIL|ERROR) in|Unable to resolve"`
Expected: ERROR — `scrape-photo` unresolved.

- [ ] **Step 3: Add `acquire-photo` + `scrape-photo`**

In `src/kaleidoscope/api/recipe_scraper.clj`, add `[kaleidoscope.api.image-transcriber :as transcriber]` to the ns `:require`, and add near `scrape-url`:

```clojure
(defn acquire-photo
  "ACQUIRE (photo): transcribe images into a :photo RawSource. The transcriber's
  technique + llm-calls become the acquire provenance. No image bytes retained."
  [{:keys [transcriber images]}]
  (let [{:keys [transcript technique llm-calls]} (transcriber/transcribe transcriber images)]
    {:source-kind       :photo
     :raw-content       transcript
     :acquire-technique technique
     :llm-calls         (vec llm-calls)}))

(defn scrape-photo
  "Photo entry point: transcribe the images, then run the one pipeline."
  [{:keys [transcriber] :as ctx} images]
  (log/infof "Running recipe photo pipeline for %d image(s)" (count images))
  (run-pipeline ctx (acquire-photo {:transcriber transcriber :images images})))
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "$(cat <<'EOF'
feat(recipes): photo pipeline path (acquire-photo + scrape-photo)

- acquire-photo transcribes images into a :photo RawSource
- scrape-photo feeds the shared run-pipeline; interpretation + provenance reused
- empty transcript -> :no-recipe-found with a persisted failed run

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 6: Boot wiring for the `:image-transcriber` component

Env-select the transcriber implementation and expose it as a system component.

**Files:**
- Modify: `src/kaleidoscope/init/env.clj` (require, boot instruction, DEFAULT list, prepare-kaleidoscope)
- Test: `test/kaleidoscope/init/env_test.clj` (create if absent)

**Interfaces:**
- Consumes: `transcriber/make-*` (Task 4).
- Produces: system component key `:image-transcriber`; env var `KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE` = `mock` | `claude-vision` | `google-vision` (default `mock`).

- [ ] **Step 1: Write the failing test**

Create (or append to) `test/kaleidoscope/init/env_test.clj`:

```clojure
(ns kaleidoscope.init.env-test
  (:require [kaleidoscope.init.env :as env]
            [kaleidoscope.api.image-transcriber :as transcriber]
            [clojure.test :refer [deftest is testing]]))

(deftest image-transcriber-boots-mock-by-default-test
  (testing "the default launcher yields a MockTranscriber under :kaleidoscope-image-transcriber"
    (let [system (env/start-system! [env/kaleidoscope-image-transcriber-boot-instructions] {})]
      (is (satisfies? transcriber/ImageTranscriber (:kaleidoscope-image-transcriber system)))))
  (testing "claude-vision launcher builds a Claude transcriber from ANTHROPIC_API_KEY"
    (let [system (env/start-system! [env/kaleidoscope-image-transcriber-boot-instructions]
                                    {"KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE" "claude-vision"
                                     "ANTHROPIC_API_KEY" "sk-test"})]
      (is (satisfies? transcriber/ImageTranscriber (:kaleidoscope-image-transcriber system))))))
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./bin/test --focus kaleidoscope.init.env-test 2>&1 | grep -E "^(FAIL|ERROR) in|Unable to resolve"`
Expected: ERROR — `kaleidoscope-image-transcriber-boot-instructions` unresolved.

- [ ] **Step 3: Add the boot instruction + wiring**

In `src/kaleidoscope/init/env.clj`:

Add to the ns `:require`: `[kaleidoscope.api.image-transcriber :as transcriber]`.

Add the boot instruction (next to `kaleidoscope-recipe-fetcher-boot-instructions`):

```clojure
(def kaleidoscope-image-transcriber-boot-instructions
  "OCR for recipe photo import. `mock` (default) returns a canned transcript for
  local dev / tests. `claude-vision` transcribes via Claude (needs
  ANTHROPIC_API_KEY). `google-vision` is the committed handwriting/dense-layout
  backend (needs GOOGLE_VISION_API_KEY)."
  {:name      :kaleidoscope-image-transcriber
   :path      "KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE"
   :launchers {"mock"          (fn [_env] (transcriber/make-mock-transcriber))
               "claude-vision" (fn [env] (transcriber/make-claude-vision-transcriber
                                          {:api-key (get env "ANTHROPIC_API_KEY")}))
               "google-vision" (fn [env] (transcriber/make-google-vision-transcriber
                                          {:api-key (get env "GOOGLE_VISION_API_KEY")}))}
   :default   "mock"})
```

Add `kaleidoscope-image-transcriber-boot-instructions` to the `DEFAULT-BOOT-INSTRUCTIONS` vector (after `kaleidoscope-recipe-fetcher-boot-instructions`).

In `prepare-kaleidoscope`, add `kaleidoscope-image-transcriber` to the destructuring `:keys`, and add to the returned map: `:image-transcriber kaleidoscope-image-transcriber`.

- [ ] **Step 4: Run env + a full boot smoke via http tests**

Run: `./bin/test --focus kaleidoscope.init.env-test --focus kaleidoscope.http-api.recipes-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: 0 failures (the http `make-app` boots `DEFAULT-BOOT-INSTRUCTIONS`, now including the transcriber).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/init/env.clj test/kaleidoscope/init/env_test.clj
git commit -m "$(cat <<'EOF'
feat(init): boot-select the image transcriber (:image-transcriber component)

- KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE = mock | claude-vision | google-vision
- default mock; exposed as :image-transcriber in prepare-kaleidoscope

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 7: `POST /recipes/scrape-photo` multipart endpoint + validation

The HTTP entry: accept multipart image uploads, validate count/type/size, run `scrape-photo`, map failures to 400/422.

**Files:**
- Modify: `src/kaleidoscope/http_api/recipes.clj` (validation helper + route)
- Test: `test/kaleidoscope/http_api/recipes_test.clj` (validation unit tests)

**Interfaces:**
- Consumes: `scraper/scrape-photo` (Task 5); `:image-transcriber` component (Task 6).
- Produces: `multipart-images [params] -> [{:content-type :bytes}]` (throws `{:type :validation :reason ..}`); route `POST /recipes/scrape-photo`.

- [ ] **Step 1: Write the failing validation tests**

Add to `test/kaleidoscope/http_api/recipes_test.clj` (add `[kaleidoscope.http-api.recipes :as recipes-http]` to the ns `:require`):

```clojure
(defn- temp-file-of [bytes]
  (let [f (java.io.File/createTempFile "recipe-img" ".bin")]
    (with-open [o (java.io.FileOutputStream. f)] (.write o ^bytes bytes))
    (.deleteOnExit f)
    f))

(defn- upload [content-type bytes]
  {:filename "x" :content-type content-type :tempfile (temp-file-of bytes) :size (alength bytes)})

(deftest multipart-images-validation-test
  (testing "reads uploaded images into {:content-type :bytes}"
    (is (match? [{:content-type "image/jpeg" :bytes bytes?}]
                (recipes-http/multipart-images {"file0" (upload "image/jpeg" (.getBytes "img"))}))))
  (testing "no image -> :no-image"
    (is (match? {:reason :no-image}
                (try (recipes-http/multipart-images {"desc" "not-a-file"})
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))))
  (testing "unsupported type -> :unsupported-type"
    (is (match? {:reason :unsupported-type}
                (try (recipes-http/multipart-images {"f" (upload "application/pdf" (.getBytes "x"))})
                     (catch clojure.lang.ExceptionInfo e (ex-data e))))))
  (testing "too many images -> :too-many-images"
    (let [six (into {} (for [i (range 6)] [(str "f" i) (upload "image/png" (.getBytes "x"))]))]
      (is (match? {:reason :too-many-images}
                  (try (recipes-http/multipart-images six)
                       (catch clojure.lang.ExceptionInfo e (ex-data e))))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test 2>&1 | grep -E "^(FAIL|ERROR) in|Unable to resolve"`
Expected: ERROR — `multipart-images` unresolved.

- [ ] **Step 3: Add the validation helper + route**

In `src/kaleidoscope/http_api/recipes.clj`:

Add to the ns `:require`: `[kaleidoscope.models.recipes :as models.recipes]` (already present). Add near the top of the file:

```clojure
(def ^:private max-images 5)
(def ^:private max-image-bytes (* 5 1024 1024))
(def ^:private allowed-image-types #{"image/jpeg" "image/png" "image/webp" "image/gif"})

(defn- file-upload?
  [x] (and (map? x) (:tempfile x) (:filename x)))

(defn multipart-images
  "Extract uploaded image files from multipart `params` into
  [{:content-type string :bytes byte-array}]. Throws ex-info {:type :validation
  :reason ..} on no image / too many / unsupported type / oversize."
  [params]
  (let [files (->> params vals (filter file-upload?))]
    (when (empty? files)
      (throw (ex-info "No image uploaded" {:type :validation :reason :no-image})))
    (when (> (count files) max-images)
      (throw (ex-info (str "At most " max-images " images per import")
                      {:type :validation :reason :too-many-images})))
    (mapv (fn [{:keys [content-type tempfile]}]
            (when-not (contains? allowed-image-types content-type)
              (throw (ex-info (str "Unsupported image type: " content-type)
                              {:type :validation :reason :unsupported-type})))
            (let [bytes (java.nio.file.Files/readAllBytes (.toPath ^java.io.File tempfile))]
              (when (> (alength bytes) max-image-bytes)
                (throw (ex-info "Image too large (max 5 MB)"
                                {:type :validation :reason :image-too-large})))
              {:content-type content-type :bytes bytes}))
          files)))
```

Add the route inside `reitit-recipes-routes`, after the `/scrape` route vector:

```clojure
   ["/scrape-photo"
    {:post {:summary    "Extract a recipe draft from uploaded photo(s) (persists raw + processing run)"
            :responses  (merge hu/openapi-401
                               {200 {:body models.recipes/ScrapeResult}
                                400 {:body [:map [:reason :string]]}
                                422 {:body [:map [:reason :string]]}})
            :handler    (fn [{:keys [components params] :as request}]
                          (try
                            (let [images (multipart-images params)
                                  ctx    {:database    (:database components)
                                          :hostname    (hu/get-host request)
                                          :api-key     (:api-key (:workflow-executor components))
                                          :transcriber (:image-transcriber components)}]
                              (try
                                (ok (scraper/scrape-photo ctx images))
                                (catch clojure.lang.ExceptionInfo e
                                  (if (= :no-recipe-found (:reason (ex-data e)))
                                    (unprocessable-entity {:reason (name (:reason (ex-data e)))})
                                    (throw e)))))
                            (catch clojure.lang.ExceptionInfo e
                              (if (= :validation (:type (ex-data e)))
                                (bad-request {:reason (name (:reason (ex-data e)))})
                                (throw e)))))}}]
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: 0 failures.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/http_api/recipes.clj test/kaleidoscope/http_api/recipes_test.clj
git commit -m "$(cat <<'EOF'
feat(recipes): POST /recipes/scrape-photo multipart endpoint

- multipart-images validates count (<=5), type (jpeg/png/webp/gif), size (<=5MB)
- runs scrape-photo; validation -> 400, no-recipe-found -> 422

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

### Task 8: End-to-end HTTP tests (auth, 422/400, scrape-photo → create lineage)

Exercise the full router + middleware for photo import, including the scrape-photo → create-recipe lineage round-trip that mirrors the URL flow.

**Files:**
- Test: `test/kaleidoscope/http_api/recipes_test.clj`

**Interfaces:**
- Consumes: `make-app`, `as-writer`, `mock/json-body` patterns already in the file; `recipes-http/multipart-images` (redef seam); default `mock` transcriber from `make-app`.

- [ ] **Step 1: Write the end-to-end tests**

Add to `test/kaleidoscope/http_api/recipes_test.clj`:

```clojure
(deftest scrape-photo-endpoint-test
  (testing "anonymous scrape-photo is rejected"
    (is (match? {:status 401}
                ((make-app "always-unauthenticated")
                 (mock/request :post "https://andrewslai.com/recipes/scrape-photo")))))
  (let [app (make-app "custom-authenticated-user")]
    (testing "a writer gets a draft back with a run-id (transcriber+LLM mocked)"
      (with-redefs [recipes-http/multipart-images
                    (fn [_] [{:content-type "image/jpeg" :bytes (.getBytes "img")}])
                    scraper/scrape-photo
                    (fn [_ _] {:recipe {:title "Mocked" :sections [{:name nil :ingredients ["a"] :steps ["Mix"]}]}
                               :suggested-labels []
                               :techniques {:acquire :claude-vision :parse :llm :normalize :single-section}
                               :warnings []
                               :scrape-processing-run-id (random-uuid)})]
        (is (match? {:status 200 :body {:recipe {:title "Mocked"}
                                        :techniques {:acquire "claude-vision" :parse "llm"}
                                        :scrape-processing-run-id string?}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))))))
    (testing "no-recipe-found surfaces as 422"
      (with-redefs [recipes-http/multipart-images (fn [_] [{:content-type "image/jpeg" :bytes (.getBytes "img")}])
                    scraper/scrape-photo (fn [_ _] (throw (ex-info "no recipe" {:type :scrape :reason :no-recipe-found})))]
        (is (match? {:status 422 :body {:reason "no-recipe-found"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))))))
    (testing "an invalid upload surfaces as 400"
      (with-redefs [recipes-http/multipart-images
                    (fn [_] (throw (ex-info "no image" {:type :validation :reason :no-image})))]
        (is (match? {:status 400 :body {:reason "no-image"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))))))))

(deftest scrape-photo-then-create-links-lineage-http-test
  (let [app (make-app "custom-authenticated-user")]
    (with-redefs [recipes-http/multipart-images
                  (fn [_] [{:content-type "image/jpeg" :bytes (.getBytes "img")}])
                  ;; default mock transcriber returns a canned transcript; the LLM
                  ;; interpretation is mocked here to yield structured facts.
                  llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Chana Masala\",\"sections\":[{\"name\":null,\"ingredients\":[\"2 cups chickpeas\"],\"steps\":[\"Cook\"]}],\"suggested_labels\":[]}"}]})]
      (let [{scrape-body :body} (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape-photo") as-writer))
            run-id (:scrape-processing-run-id scrape-body)]
        (testing "the scrape-photo response carries a run-id and techniques"
          (is (match? {:recipe {:title "Chana Masala"} :techniques {:acquire "claude-vision" :parse "llm"}}
                      scrape-body))
          (is (string? run-id)))
        (testing "creating a recipe with the run-id persists the FK; round-trips via GET"
          (app (-> (mock/request :post "https://andrewslai.com/recipes")
                   as-writer
                   (mock/json-body {:content (:recipe scrape-body)
                                    :public-visibility true
                                    :scrape-processing-run-id run-id})))
          (is (match? {:status 200 :body {:recipe-url "chana-masala"
                                          :scrape-processing-run-id run-id}}
                      (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))))
```

Add `[kaleidoscope.workflows.llm-executor :as llm]` to the ns `:require` if absent.

- [ ] **Step 2: Run the tests to verify they fail, then pass**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test 2>&1 | grep -E "^(FAIL|ERROR) in|Ran"`
Expected: initially may fail if the route/wiring has a gap; iterate until 0 failures. (If the multipart middleware rejects a bodyless POST, note that `multipart-images` is redef'd so the body is never parsed — the request needs no multipart body.)

- [ ] **Step 3: Run the full suite**

Run: `task test:summary 2>&1 | tail -30`
Expected: no new failures across the suite (migration touches the shared corpus table; confirm `scrape-pipeline`, `recipes`, `recipe-scraper`, and any recipe API tests are green).

- [ ] **Step 4: Commit**

```bash
git add test/kaleidoscope/http_api/recipes_test.clj
git commit -m "$(cat <<'EOF'
test(recipes): end-to-end photo import (auth, 400/422, scrape-photo->create lineage)

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Self-Review

**Spec coverage:**
- Claude-vision OCR default + Google Vision committed 2nd impl → Task 4, Task 6. ✓
- OCR/interpretation decomplected; interpretation reuses `parse-text` → Task 2, Task 5 (photo → `parse-text`). ✓
- `RawSource` value + single `run-pipeline` → Task 3. ✓
- `raw_content` + `source_kind` honest names; `request_url` nullable → Task 1. ✓
- `techniques` map replaces `extraction-method` → Task 3. ✓
- Provenance parity (raw_scrapes + processing_runs on success & failure) → Task 5 tests. ✓
- Multipart endpoint, limits (≤5 images, ≤5MB, type allowlist), 400/422 mapping → Task 7. ✓
- No image bytes retained (only transcript) → Task 4 (`transcribe` returns text), Task 5 (`acquire-photo` stores transcript). ✓
- Boot wiring `:image-transcriber` → Task 6. ✓
- Tests every layer on embedded-h2 → Tasks 1–8. ✓

**Placeholder scan:** No TBD/TODO; every code step shows full code; every test shows assertions. ✓

**Type consistency:** `transcribe` returns `{:transcript :technique :llm-calls}` (Task 4) consumed by `acquire-photo` (Task 5). `RawSource` keys (`:source-kind :raw-content :acquire-technique :llm-calls`) consistent across `acquire-url`/`acquire-photo`/`process`/`run-pipeline` (Tasks 3, 5). `create-raw-scrape!` consumes `:source-kind :raw-content` (Task 1) matching what `run-pipeline` passes (Task 3). `scrape-photo`/`scrape-url` both 2-arg `[ctx x]` → `run-pipeline [ctx raw-source]`. ✓

**Open verification (flagged during implementation):**
- Portable DDL: `RENAME COLUMN`, `ADD COLUMN`, `ALTER COLUMN ... SET NOT NULL` / `DROP NOT NULL` must run on embedded-H2 (test boot runs migrations). Task 1 Step 8 catches dialect issues; if H2 rejects a form, split into H2-compatible statements.
- Multipart middleware: `wrap-multipart-params` is applied app-wide (see `http_api/middleware.clj`); the `/scrape-photo` handler reads `params` like `http_api/photo.clj`. Confirmed pattern; Task 8 exercises the wired route via a `multipart-images` redef seam.
