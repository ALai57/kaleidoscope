# Recipe Import Lineage Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Expose a recipe's import lineage — its scrape processing run and the raw scrape it ran over — through a writer-only read endpoint, so the site owner can inspect and debug what each pipeline stage did.

**Architecture:** Pure read assembly over records the scrape pipeline already persists. A new domain function `get-recipe-lineage` (in `api/recipes.clj`) resolves `recipe → processing_run → raw_scrape` via the existing persistence readers and returns one `RecipeLineage` map. A new `GET /recipes/:recipe-url/lineage` route gates on writer role and serializes that map. No DB migration, no new capture.

**Tech Stack:** Clojure, reitit (routing + Malli coercion), next.jdbc + HoneySQL, Malli (`models/recipes.cljc`), Kaocha + matcher-combinators, embedded-postgres / embedded-h2 for tests.

## Global Constraints

- **3-layer separation:** `http_api/` never calls persistence directly; `api/` has no HTTP concerns. Lineage assembly lives in `api/recipes.clj` (may call `persistence/scrape_pipeline.clj`); the writer gate and HTTP mapping live in `http_api/recipes.clj`.
- **No schema changes / no migration.** Every field read already exists on `processing_runs`, `raw_scrapes`, and `recipes`.
- **Every feature needs automated tests** (CLAUDE.md sharp edge #4).
- **Writer-only.** The endpoint exposes raw HTML/transcripts and full LLM prompts; non-writers (anonymous or reader) receive **404** (no existence leak).
- **`!` suffix** on side-effecting fns only; `get-recipe-lineage` is a pure read (no `!`).
- **SQL is `snake_case`, Clojure is `kebab-case`** — conversion is automatic; the persistence readers already return kebab-case maps.
- **Recipe tests run on embedded-postgres** at the `api/` layer (jsonb features); the `http/` layer test may use embedded-h2 (matches the existing `recipes_test` http suite).
- Run one test at a time with `./bin/test --focus <ns>/<test>`; use `task test:summary` for failures without stack traces.

---

## File Structure

- `src/kaleidoscope/api/recipes.clj` — **modify.** Add `require` of `persistence.scrape-pipeline` and the `get-recipe-lineage` domain function. (Task 1)
- `test/kaleidoscope/api/recipes_test.clj` — **modify.** Add `recipe-import-lineage-test`. (Task 1)
- `src/kaleidoscope/models/recipes.cljc` — **modify.** Add the `RecipeLineage` response schema. (Task 2)
- `src/kaleidoscope/http_api/recipes.clj` — **modify.** Add the `GET /:recipe-url/lineage` route with the writer gate. (Task 2)
- `test/kaleidoscope/http_api/recipes_test.clj` — **modify.** Add `import-lineage-http-test`. (Task 2)

---

## Task 1: `get-recipe-lineage` domain assembly

**Files:**
- Modify: `src/kaleidoscope/api/recipes.clj` (add require + function near the "Recipes" section, after `get-recipe`)
- Test: `test/kaleidoscope/api/recipes_test.clj`

**Interfaces:**
- Consumes (existing):
  - `kaleidoscope.api.recipes/get-recipe [db hostname recipe-url] -> recipe-map | nil` — the returned map includes `:id` and `:scrape-processing-run-id`.
  - `kaleidoscope.persistence.scrape-pipeline/get-processing-run [db id hostname] -> run-map | nil` — run map has kebab keys `:id :raw-scrape-id :pipeline-version :techniques :facts :content :llm-calls :warnings :outcome :error-detail :created-at`. JSONB fields come back **decoded** (maps/vectors); `:techniques`/`:outcome` values are **strings** (e.g. `"json-ld"`, `"success"`).
  - `kaleidoscope.persistence.scrape-pipeline/get-raw-scrape [db id hostname] -> raw-map | nil` — raw map has `:source-kind :request-url :final-url :http-status :fetch-tier :raw-content :created-at`.
- Produces (new):
  - `kaleidoscope.api.recipes/get-recipe-lineage [db hostname recipe-url include-raw?] -> lineage-map | nil`
    - Returns `nil` when the recipe doesn't exist for `hostname`, or exists but has no `:scrape-processing-run-id`.
    - Shape:
      ```clojure
      {:recipe-url "chana-masala"
       :recipe-id  #uuid "…"
       :run  {:id … :pipeline-version "…" :outcome "success" :error-detail nil
              :techniques {…} :facts {…} :content {…}
              :llm-calls [{…}] :warnings ["…"] :created-at #inst "…"}
       :raw  {:source-kind "url" :request-url "…" :final-url "…" :http-status 200
              :fetch-tier "direct" :content-bytes 19 :created-at #inst "…"
              ;; :raw-content present only when include-raw? is true
              }}
      ```

- [ ] **Step 1: Write the failing test**

Add to the end of `test/kaleidoscope/api/recipes_test.clj` (the `pipeline-db` require is already present in that file's `ns` form):

```clojure
(deftest recipe-import-lineage-test
  (let [db       (embedded-pg/fresh-db!)
        raw-html "<html>recipe</html>"
        {raw-id :id} (pipeline-db/create-raw-scrape!
                      db {:hostname host :source-kind "url"
                          :request-url "http://example.com/r" :final-url "http://example.com/r"
                          :http-status 200 :fetch-tier "direct" :raw-content raw-html})
        {run-id :id} (pipeline-db/create-processing-run!
                      db {:hostname host :raw-scrape-id raw-id :pipeline-version "abc123"
                          :techniques {:acquire :direct :parse :json-ld :normalize :single-section}
                          :facts   {:title "Chana Masala" :ingredients ["2 cups chickpeas"]
                                    :steps ["Cook"] :section-signals [] :labels []}
                          :content example-content
                          :llm-calls [] :warnings [] :outcome :success :error-detail nil})
        _        (recipes/create-recipe! db (example-recipe :scrape-processing-run-id run-id))]

    (testing "assembles run + raw for a scraped recipe; raw body omitted by default"
      (is (match? {:recipe-url "chana-masala"
                   :recipe-id  uuid?
                   :run  {:pipeline-version "abc123"
                          :outcome          "success"
                          :techniques       {:parse "json-ld"}
                          :content          {:title "Chana Masala"}}
                   :raw  {:http-status   200
                          :fetch-tier    "direct"
                          :content-bytes (count raw-html)
                          :raw-content   nil?}}
                  (recipes/get-recipe-lineage db host "chana-masala" false))))

    (testing "include-raw? returns the stored raw body"
      (is (match? {:raw {:raw-content raw-html}}
                  (recipes/get-recipe-lineage db host "chana-masala" true))))

    (testing "a recipe with no linked run has no lineage"
      (recipes/create-recipe! db (example-recipe :recipe-url "no-run"))
      (is (nil? (recipes/get-recipe-lineage db host "no-run" false))))

    (testing "a nonexistent recipe has no lineage"
      (is (nil? (recipes/get-recipe-lineage db host "does-not-exist" false))))

    (testing "scoped to hostname — another host sees nothing"
      (is (nil? (recipes/get-recipe-lineage db "other.com" "chana-masala" false))))))
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipes-test/recipe-import-lineage-test`
Expected: FAIL — `Unable to resolve symbol: get-recipe-lineage` (or "No such var").

- [ ] **Step 3: Add the require**

In `src/kaleidoscope/api/recipes.clj`, add the persistence require to the `ns` form (it currently requires `kaleidoscope.persistence.rdbms` but not the pipeline namespace):

```clojure
            [kaleidoscope.persistence.rdbms :as rdbms]
            [kaleidoscope.persistence.scrape-pipeline :as pipeline-db]
```

- [ ] **Step 4: Implement `get-recipe-lineage`**

In `src/kaleidoscope/api/recipes.clj`, add this immediately after `get-recipe` (the single-recipe reader in the "Recipes" section):

```clojure
(defn get-recipe-lineage
  "A recipe's import lineage: the processing run linked via
  `:scrape-processing-run-id` and the raw scrape that run ran over, all scoped
  to `hostname`. Pure read — assembles records the pipeline already persisted.

  Returns nil when no recipe with that slug exists for the host, or when it has
  no linked run (a manually-created recipe). `include-raw?` gates the
  potentially large raw body: when false the `:raw` map carries `:content-bytes`
  (size only) and omits `:raw-content`; when true it carries the full body."
  [db hostname recipe-url include-raw?]
  (when-let [{:keys [id scrape-processing-run-id]} (get-recipe db hostname recipe-url)]
    (when scrape-processing-run-id
      (when-let [run (pipeline-db/get-processing-run db scrape-processing-run-id hostname)]
        (let [raw (pipeline-db/get-raw-scrape db (:raw-scrape-id run) hostname)]
          {:recipe-url recipe-url
           :recipe-id  id
           :run        (select-keys run [:id :pipeline-version :outcome :error-detail
                                         :techniques :facts :content :llm-calls
                                         :warnings :created-at])
           :raw        (-> raw
                           (select-keys [:source-kind :request-url :final-url
                                         :http-status :fetch-tier :raw-content :created-at])
                           (assoc :content-bytes (count (or (:raw-content raw) "")))
                           (cond-> (not include-raw?) (dissoc :raw-content)))})))))
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.recipes-test/recipe-import-lineage-test`
Expected: PASS (all five `testing` blocks).

- [ ] **Step 6: Commit**

```bash
git add src/kaleidoscope/api/recipes.clj test/kaleidoscope/api/recipes_test.clj
git commit -m "feat(recipes): get-recipe-lineage assembles run + raw scrape

- Pure read resolving recipe -> processing_run -> raw_scrape, hostname-scoped
- include-raw? gates the raw body; default returns content-bytes only
- Returns nil for a missing recipe or one with no linked run

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: `RecipeLineage` model + `GET /:recipe-url/lineage` route

**Files:**
- Modify: `src/kaleidoscope/models/recipes.cljc` (add `RecipeLineage` after `ScrapeResult`)
- Modify: `src/kaleidoscope/http_api/recipes.clj` (add route inside `reitit-recipes-routes`, after the `/:recipe-url` block)
- Test: `test/kaleidoscope/http_api/recipes_test.clj`

**Interfaces:**
- Consumes (from Task 1): `recipes-api/get-recipe-lineage [db hostname recipe-url include-raw?] -> lineage-map | nil`.
- Consumes (existing): `authz/writer? [request] -> boolean`; `hu/get-host`, `hu/openapi-401`, `hu/openapi-404`; `ring.util.http-response/{ok,not-found}` (all already required in `http_api/recipes.clj`).
- Produces (new):
  - `models.recipes/RecipeLineage` — Malli schema for the response body.
  - Route `GET /recipes/:recipe-url/lineage` with optional `?include-raw=<bool>` query param.

- [ ] **Step 1: Write the failing test**

Add to `test/kaleidoscope/http_api/recipes_test.clj`. It drives the **real** pipeline (fetch stubbed at the `fetch-direct` boundary) so a genuine run+raw is persisted, links a recipe to it, then reads the lineage back. Add a local `json-ld-html` def near the top of the file (below `example-body`) and the test at the end:

```clojure
(def json-ld-html
  "<html><head>
   <script type=\"application/ld+json\">
   {\"@context\":\"https://schema.org\",\"@type\":\"Recipe\",\"name\":\"Chana Masala\",
    \"recipeIngredient\":[\"2 cups chickpeas\"],
    \"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Cook\"}]}
   </script></head><body>Blog exposition.</body></html>")
```

```clojure
(deftest import-lineage-http-test
  (let [app (make-app "custom-authenticated-user")]
    (with-redefs [scraper/fetch-direct (fn [_] {:raw-html json-ld-html
                                                :final-url "http://example.com/r"
                                                :http-status 200})]
      ;; 1. scrape persists a raw scrape + processing run, returns the run id
      (let [scrape (app (-> (mock/request :post "https://andrewslai.com/recipes/scrape")
                            as-writer
                            (mock/json-body {:url "http://example.com/r"})))
            run-id (get-in scrape [:body :scrape-processing-run-id])]

        (testing "precondition: the scrape returned a run id"
          (is (string? run-id)))

        ;; 2. create the recipe linked to that run
        (app (-> (mock/request :post "https://andrewslai.com/recipes")
                 as-writer
                 (mock/json-body (assoc example-body :scrape-processing-run-id run-id))))

        (testing "a writer reads the assembled lineage; raw body omitted by default"
          (is (match? {:status 200
                       :body   {:recipe-url "chana-masala"
                                :run  {:outcome    "success"
                                       :techniques {:parse "json-ld"}}
                                :raw  {:http-status   200
                                       :content-bytes pos-int?
                                       :raw-content   nil?}}}
                      (app (-> (mock/request :get "https://andrewslai.com/recipes/chana-masala/lineage")
                               as-writer)))))

        (testing "include-raw=true returns the stored raw html"
          (is (match? {:status 200 :body {:raw {:raw-content json-ld-html}}}
                      (app (-> (mock/request :get "https://andrewslai.com/recipes/chana-masala/lineage?include-raw=true")
                               as-writer)))))

        (testing "a recipe with no linked run has no lineage (404)"
          (app (-> (mock/request :post "https://andrewslai.com/recipes")
                   as-writer
                   (mock/json-body (assoc example-body :recipe-url "manual" :content
                                          {:title "Manual" :sections [{:ingredients ["x"] :steps ["y"]}]}))))
          (is (match? {:status 404}
                      (app (-> (mock/request :get "https://andrewslai.com/recipes/manual/lineage")
                               as-writer)))))))

    (testing "a non-writer cannot see lineage (404, no existence leak)"
      (is (match? {:status 404}
                  ((make-app "always-unauthenticated")
                   (mock/request :get "https://andrewslai.com/recipes/chana-masala/lineage")))))))
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test/import-lineage-http-test`
Expected: FAIL — the lineage GET returns `404` for the writer (route not defined yet), so the "reads the assembled lineage" assertion fails on `:status 200`.

- [ ] **Step 3: Add the `RecipeLineage` model**

In `src/kaleidoscope/models/recipes.cljc`, add after `ScrapeResult`. It reuses `ExtractedFacts` and `RecipeContent` so the lineage view cannot drift from the pipeline's artifact shapes:

```clojure
(def LlmCall
  ;; A stored Anthropic call. `purpose` round-trips from JSONB as a string;
  ;; request/response are stored verbatim, so they stay opaque maps here.
  [:map
   [:purpose  some?]
   [:model    :string]
   [:request  :map]
   [:response :map]])

(def RecipeLineage
  ;; Read-only view assembled from processing_runs + raw_scrapes for one recipe.
  ;; JSONB-sourced values arrive decoded; `techniques`/`outcome` are strings.
  [:map
   [:recipe-url :string]
   [:recipe-id  :uuid]
   [:run  [:map
           [:id               :uuid]
           [:pipeline-version :string]
           [:outcome          :string]
           [:error-detail     [:maybe :map]]
           [:techniques       [:map
                               [:acquire   {:optional true} [:maybe :string]]
                               [:parse     {:optional true} [:maybe :string]]
                               [:normalize {:optional true} [:maybe :string]]]]
           [:facts            [:maybe ExtractedFacts]]
           [:content          [:maybe RecipeContent]]
           [:llm-calls        [:sequential LlmCall]]
           [:warnings         [:sequential :string]]
           [:created-at       some?]]]
   [:raw  [:map
           [:source-kind   :string]
           [:request-url   {:optional true} [:maybe :string]]
           [:final-url     {:optional true} [:maybe :string]]
           [:http-status   {:optional true} [:maybe :int]]
           [:fetch-tier    {:optional true} [:maybe :string]]
           [:content-bytes :int]
           [:raw-content   {:optional true} [:maybe :string]]
           [:created-at    some?]]]])
```

- [ ] **Step 4: Add the route**

In `src/kaleidoscope/http_api/recipes.clj`, inside `reitit-recipes-routes`, add this route vector immediately after the `["/:recipe-url" {…}]` block (and before the closing `]` of `reitit-recipes-routes`):

```clojure
   ["/:recipe-url/lineage"
    {:get {:summary    "Import lineage for a recipe (writer-only): processing run + raw scrape"
           :responses  (merge hu/openapi-401 hu/openapi-404
                              {200 {:body models.recipes/RecipeLineage}})
           :parameters {:path  {:recipe-url :string}
                        :query [:map [:include-raw {:optional true} :boolean]]}
           :handler    (fn [{:keys [components parameters] :as request}]
                         ;; Writer-only: the response exposes raw HTML/transcripts
                         ;; and full LLM prompts. Non-writers get 404 (no leak).
                         (if-not (authz/writer? request)
                           (not-found {:reason "Missing"})
                           (if-let [lineage (recipes-api/get-recipe-lineage
                                             (:database components)
                                             (hu/get-host request)
                                             (get-in parameters [:path :recipe-url])
                                             (boolean (get-in parameters [:query :include-raw])))]
                             (ok lineage)
                             (not-found {:reason "Missing"}))))}}]
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test/import-lineage-http-test`
Expected: PASS (all `testing` blocks).

- [ ] **Step 6: Run the full recipe test namespaces to check nothing regressed**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test --focus kaleidoscope.api.recipes-test`
Expected: PASS. (If the model reuse of `ExtractedFacts`/`RecipeContent` rejects a real stored run during response coercion, that surfaces here — loosen the offending field to `[:maybe :map]` and re-run.)

- [ ] **Step 7: Commit**

```bash
git add src/kaleidoscope/models/recipes.cljc src/kaleidoscope/http_api/recipes.clj test/kaleidoscope/http_api/recipes_test.clj
git commit -m "feat(recipes): writer-only GET /recipes/:recipe-url/lineage

- Serializes get-recipe-lineage as RecipeLineage (reuses ExtractedFacts /
  RecipeContent so the view can't drift from the pipeline)
- include-raw query param gates the raw body; default omits it
- Non-writers get 404 (no existence leak); missing recipe/run -> 404

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Self-Review

**1. Spec coverage:**
- Writer-only `GET /recipes/:recipe-url/lineage` → Task 2 route + gate. ✓
- `?include-raw` gating raw body, default = metadata + `content-bytes` → Task 1 assembly + Task 2 query param; tested both layers. ✓
- 404 for non-writer / missing recipe / missing run → Task 1 returns nil for missing recipe/run; Task 2 maps writer-gate + nil → 404; tested. ✓
- `RecipeLineage` response shape reusing `ExtractedFacts`/`RecipeContent` → Task 2 model. ✓
- Pure assembly over existing readers, no migration → Task 1 reuses `get-processing-run`/`get-raw-scrape`; no migration file in plan. ✓
- Token usage read from `llm-calls[].response.usage`, no fabricated timing → the endpoint returns `:llm-calls` verbatim (usage included); no timing field is invented. ✓ (UI derives token totals; nothing to compute server-side.)
- Hostname scoping → Task 1 passes `hostname` to both readers; tested with `other.com`. ✓
- Frontend contract → documented in DESIGN.md; the UI build is out of scope for this repo. ✓ (no task, by design)

**2. Placeholder scan:** No TBD/TODO; every code step shows complete code; every command shows expected output. ✓

**3. Type consistency:** `get-recipe-lineage [db hostname recipe-url include-raw?]` is defined identically in Task 1 and consumed with the same argument order in Task 2. `RecipeLineage` field names (`:content-bytes`, `:raw-content`, `:run`, `:raw`) match the map keys produced in Task 1. `:techniques`/`:outcome` typed as strings in both the model and the Task 1 interface note (JSONB → string). ✓

---

## Notes for the implementer

- **Why writer-only returns 404, not 403:** consistent with the recipe GET, which returns 404 for a recipe the caller may not see — we don't reveal that a lineage exists to someone not entitled to read it.
- **Why `include-raw` is a query param, not a separate endpoint:** one resource, one route; the large body is opt-in. The UI fetches metadata for the strip and pulls the raw body only when the ACQUIRE inspector is expanded (see DESIGN.md → Frontend contract).
- **No `docs/operations.md` update needed:** this adds an HTTP route only — no change to deployment, Taskfile, or `bin/` (CLAUDE.md sharp edge #6 does not apply).
