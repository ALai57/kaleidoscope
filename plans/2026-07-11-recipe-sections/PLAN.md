# Recipe Sections Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make sectioned, paired components (e.g. Cake + Frosting, each owning its ingredients AND steps) the one and only `RecipeContent` shape, with steps as data (no HTML) and a scraper split into deterministic JSON-LD extraction + a constrained, index-based LLM grouping call.

**Architecture:** `RecipeContent` becomes `{title, sections: [{name?, ingredients: [string], steps: [string]}], servings?, times?}` — one shape, no legacy variants (spec: `plans/2026-07-11-recipe-sections/DESIGN.md`). The domain layer only changes its Postgres ingredient-filter SQL; the scraper is decomposed so `parse-json-ld` returns verbatim facts and `scrape` assembles sections, calling Haiku *only* to group by index when the page shows section signals.

**Tech Stack:** Clojure, Malli (schemas in `.cljc`), HoneySQL/next.jdbc, Kaocha + matcher-combinators, embedded-postgres for recipe tests.

## Global Constraints

- The feature branch (`plans/recipes-feature`) is unmerged: the sectioned shape is the ONLY shape — never write read-time normalization, legacy dual-path SQL, or flat-shape fallbacks.
- `original-content` is immutable — no task touches it after create.
- 3-layer separation: no persistence from `http_api/`, no HTTP from `api/`.
- No DB schema changes (content is opaque JSONB); the only migration edit is a comment.
- Recipe API tests run on **embedded-postgres** (`embedded-pg/fresh-db!`), not H2.
- Test runs: `./bin/test --focus <ns>` per namespace; `task test:summary` for the full suite. Never dump full failing-suite output — grep per CLAUDE.md.
- The working tree has pre-existing uncommitted changes (e.g. `src/kaleidoscope/api/authorization.clj`). Commit ONLY the files each task names — never `git add -A`.
- LLM calls use the existing `fallback-model` (`claude-haiku-4-5`) via `kaleidoscope.workflows.llm-executor/post-anthropic-sync`; tests stub that var, never the network.

---

### Task 1: Sectioned `RecipeContent` shape — model, domain SQL, all fixtures

**Files:**
- Modify: `src/kaleidoscope/models/recipes.cljc`
- Modify: `src/kaleidoscope/api/recipes.clj:1-8` (ns docstring), `:147-157` (ingredient filter)
- Test: `test/kaleidoscope/api/recipes_test.clj`
- Test: `test/kaleidoscope/http_api/recipes_test.clj`

**Interfaces:**
- Produces: `models.recipes/RecipeSection` = `[:map [:name {:optional true} [:maybe :string]] [:ingredients [:sequential :string]] [:steps [:sequential :string]]]`; `RecipeContent` with `[:sections [:sequential {:min 1} RecipeSection]]` (no top-level `:ingredients`/`:instructions-html`); `ScrapeResult`'s `:extraction-method` enum `["json-ld" "json-ld+llm-sections" "llm"]`. Tasks 2–4 build scraper output that must validate against these.
- Consumes: nothing new.

- [ ] **Step 1: Update the API-layer test fixtures and add the second-section search test**

In `test/kaleidoscope/api/recipes_test.clj`, replace `example-content` (lines 20–26):

```clojure
(def example-content
  {:title             "Chana Masala"
   :sections          [{:name        nil
                        :ingredients ["2 cups chickpeas" "1 tbsp flour" "1 onion"]
                        :steps       ["Soak the chickpeas" "Cook"]}]
   :servings          "4"
   :prep-time-minutes 15
   :cook-time-minutes 30})
```

Replace the whole `ingredient-search-test` (lines 155–165):

```clojure
(deftest ingredient-search-test
  (let [db (embedded-pg/fresh-db!)]
    (recipes/create-recipe! db (example-recipe
                                :recipe-url "cake"
                                :content {:title    "Layer Cake"
                                          :sections [{:name        "Cake"
                                                      :ingredients ["2 cups flour" "1 cup sugar"]
                                                      :steps       ["Mix" "Bake"]}
                                                     {:name        "Frosting"
                                                      :ingredients ["1 cup butter" "powdered sugar"]
                                                      :steps       ["Whip"]}]}))
    (recipes/create-recipe! db (example-recipe
                                :recipe-url "salad"
                                :content {:title    "Salad"
                                          :sections [{:name        nil
                                                      :ingredients ["lettuce" "tomato"]
                                                      :steps       ["Toss"]}]}))
    (testing "matches an ingredient line inside the SECOND section"
      (is (match? [{:recipe-url "cake"}]
                  (recipes/get-recipes db {:hostname host :ingredient "butter"}))))
    (testing "text-contains match across all sections and recipes"
      (is (= 2 (count (recipes/get-recipes db {:hostname host :ingredient "o"})))))
    (testing "no match returns empty"
      (is (empty? (recipes/get-recipes db {:hostname host :ingredient "beef"}))))))
```

- [ ] **Step 2: Update the HTTP-layer test fixtures**

In `test/kaleidoscope/http_api/recipes_test.clj`, replace `example-body` (lines 37–41):

```clojure
(def example-body
  {:content {:title    "Chana Masala"
             :sections [{:ingredients ["2 cups chickpeas" "1 tbsp flour"]
                         :steps       ["Cook"]}]}
   :public-visibility true})
```

In `scrape-endpoint-test`, replace the mocked draft (lines 121–124):

```clojure
      (with-redefs [scraper/scrape (fn [_ _] {:recipe {:title    "Mocked"
                                                       :sections [{:name        nil
                                                                   :ingredients ["a"]
                                                                   :steps       ["Mix"]}]}
                                              :suggested-labels []
                                              :extraction-method "json-ld"
                                              :warnings []})]
```

- [ ] **Step 3: Run both test namespaces to verify they fail for the right reason**

Run: `./bin/test --focus kaleidoscope.api.recipes-test` and `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: FAIL — `ingredient-search-test` finds nothing (old SQL reads `content -> 'ingredients'`, now absent); HTTP creates fail Malli coercion (`CreateRecipeRequest` still requires top-level `:ingredients`).

- [ ] **Step 4: Rewrite the model schemas**

In `src/kaleidoscope/models/recipes.cljc`, replace `RecipeContent` (lines 3–13) with:

```clojure
;; The single recipe-content value shape. Both the current recipe (`content`)
;; and the immutable scrape (`original-content`) validate against this, so they
;; cannot drift. Sections are the ONLY representation: a simple recipe is one
;; unnamed section. Steps are plain text — HTML rendering belongs to the UI.
(def RecipeSection
  [:map
   [:name        {:optional true} [:maybe :string]] ;; absent/nil ⇒ unnamed
   [:ingredients [:sequential :string]]             ;; verbatim freeform lines
   [:steps       [:sequential :string]]])           ;; plain text, one per step

(def RecipeContent
  [:map
   [:title             :string]
   [:sections          [:sequential {:min 1} RecipeSection]]
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]])
```

In `ScrapeResult`, replace the extraction-method line:

```clojure
   [:extraction-method [:enum "json-ld" "json-ld+llm-sections" "llm"]]
```

- [ ] **Step 5: Update the domain ingredient filter and ns docstring**

In `src/kaleidoscope/api/recipes.clj`, update the ns docstring's content sentence (lines 5–8) to:

```clojure
  Identity is a single opaque UUID; `recipe-url` is the slug/address. A recipe's
  content is one JSONB value under `:content`: {title, sections [{name?,
  ingredients [string], steps [string]}], servings?, times?} — sections pair a
  component's ingredients with its steps (see
  plans/2026-07-11-recipe-sections/DESIGN.md). The immutable scrape is the same
  shape under `:original-content`.
```

In `get-recipes`, replace the `ingredient` clause (lines 150–152) with:

```clojure
                ingredient (conj [:exists {:select [[[:inline 1]]]
                                           :from   [[[:raw "jsonb_array_elements(content -> 'sections')"] :s]
                                                    [[:raw "jsonb_array_elements_text(s.value -> 'ingredients')"] :i]]
                                           :where  [:ilike :i (str "%" ingredient "%")]}])
```

(Postgres treats set-returning functions in `FROM` as implicit `LATERAL`, so `s.value` is visible to the second function. `jsonb_array_elements` exposes its jsonb as column `value` under table alias `s`.)

Also update the `get-recipes` docstring's `:ingredient` line to: `- :ingredient  text-contains over every section's ingredients array (Postgres only)`.

- [ ] **Step 6: Run both namespaces to verify they pass**

Run: `./bin/test --focus kaleidoscope.api.recipes-test` then `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: PASS (scraper tests are untouched and still pass — the scraper isn't Malli-validated internally and changes in Task 2).

- [ ] **Step 7: Commit**

```bash
git add src/kaleidoscope/models/recipes.cljc src/kaleidoscope/api/recipes.clj \
        test/kaleidoscope/api/recipes_test.clj test/kaleidoscope/http_api/recipes_test.clj
git commit -m "feat(recipes): sectioned RecipeContent is the only shape

- RecipeSection {name?, ingredients [string], steps [string]}; RecipeContent requires >=1 section; instructions-html removed from the domain
- Ingredient filter traverses sections via implicit-LATERAL jsonb_array_elements
- extraction-method enum gains json-ld+llm-sections

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: Scraper extraction — verbatim facts, single-section assembly, delete HTML rendering

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj:158-202` (`instructions->html` → `parse-instructions`; `parse-json-ld` → facts), `:244-257` (`scrape`)
- Test: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Produces: `parse-json-ld : html → facts|nil` where facts = `{:title string, :ingredients [string], :steps [string], :section-names [string], :servings string?, :prep-time-minutes int?, :cook-time-minutes int?, :suggested-labels [string]}`; private `facts->result [facts sections extraction-method warnings] → ScrapeResult`; private `single-section [facts] → [{:name nil :ingredients … :steps …}]`. Task 3 consumes all three.
- Consumes: `models.recipes/RecipeContent` shape from Task 1 (scrape output must validate against it via the HTTP layer's `ScrapeResult` coercion).

- [ ] **Step 1: Rewrite the JSON-LD scraper tests against the facts shape**

In `test/kaleidoscope/api/recipe_scraper_test.clj`, replace `json-ld-happy-path-test`, `json-ld-graph-wrapper-test`, `json-ld-howto-section-test` (lines 37–56) with:

```clojure
(deftest json-ld-happy-path-test
  (is (match? {:title             "Chana Masala"
               :ingredients       ["2 cups chickpeas" "1 tbsp flour"]
               :steps             ["Soak" "Cook"]
               :section-names     []
               :servings          "4"
               :prep-time-minutes 15
               :cook-time-minutes 30
               :suggested-labels  #(contains? (set %) "Indian")}
              (scraper/parse-json-ld json-ld-html))))

(deftest json-ld-graph-wrapper-test
  (let [html "<script type='application/ld+json'>{\"@graph\":[{\"@type\":\"WebPage\"},{\"@type\":\"Recipe\",\"name\":\"Soup\",\"recipeIngredient\":[\"water\"],\"recipeInstructions\":\"Boil water\"}]}</script>"]
    (is (match? {:title "Soup" :steps ["Boil water"] :section-names []}
                (scraper/parse-json-ld html)))))

(deftest json-ld-howto-section-test
  (testing "HowToSection names become candidate section-names; steps stay verbatim and ordered"
    (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"X\",\"recipeIngredient\":[],\"recipeInstructions\":[{\"@type\":\"HowToSection\",\"name\":\"Cake\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"A\"},{\"@type\":\"HowToStep\",\"text\":\"B\"}]},{\"@type\":\"HowToSection\",\"name\":\"Frosting\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"C\"}]}]}</script>"]
      (is (match? {:steps ["A" "B" "C"] :section-names ["Cake" "Frosting"]}
                  (scraper/parse-json-ld html))))))

(deftest unsectioned-scrape-assembles-single-section-test
  (testing "an unsectioned JSON-LD scrape yields one unnamed section and NO LLM call"
    (with-redefs [scraper/fetch-direct (fn [_] json-ld-html)
                  llm/post-anthropic-sync (fn [_ _] (throw (ex-info "LLM must not be called" {})))]
      (is (match? {:recipe {:title    "Chana Masala"
                            :sections [{:name        nil?
                                        :ingredients ["2 cups chickpeas" "1 tbsp flour"]
                                        :steps       ["Soak" "Cook"]}]
                            :servings "4"}
                   :extraction-method "json-ld"
                   :warnings          []}
                  (scraper/scrape {:api-key "sk-test"} public-url))))))
```

Add the alias to the ns `:require`: `[kaleidoscope.workflows.llm-executor :as llm]`, and change the existing `llm-fallback-invoked-test` redef target from the fully-qualified `kaleidoscope.workflows.llm-executor/post-anthropic-sync` to `llm/post-anthropic-sync` (its response payload is updated in Task 4 — leave the payload alone here; it still exercises the old prompt mapping until then, so ALSO leave `bot-blocked-falls-back-to-fetcher-test` untouched: it matches only `{:recipe {:title …}}` which holds in both shapes).

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`
Expected: FAIL — `parse-json-ld` still returns `{:recipe …}` with `:instructions-html`, and `unsectioned-scrape-assembles-single-section-test` finds no `:sections`.

- [ ] **Step 3: Rewrite extraction in the scraper**

In `src/kaleidoscope/api/recipe_scraper.clj`, replace `instructions->html` (lines 158–175) with:

```clojure
(defn- parse-instructions
  "schema.org recipeInstructions → {:steps [string] :section-names [string]}.
  Steps are verbatim HowToStep text in document order; section-names are
  non-blank HowToSection names in order — the sectioning signal consumed by
  the grouping step. Accepts a plain string, HowToStep[], or HowToSection[]."
  [instructions]
  (let [add-step (fn [acc s]
                   (cond-> acc (not (str/blank? (or s ""))) (update :steps conj s)))]
    (cond
      (string? instructions)
      (add-step {:steps [] :section-names []} instructions)

      (sequential? instructions)
      (reduce (fn [acc item]
                (cond
                  (string? item)
                  (add-step acc item)

                  (= "HowToSection" (get item (keyword "@type")))
                  (reduce add-step
                          (cond-> acc
                            (not (str/blank? (or (:name item) "")))
                            (update :section-names conj (:name item)))
                          (map :text (:itemListElement item)))

                  :else
                  (add-step acc (:text item))))
              {:steps [] :section-names []}
              instructions)

      :else {:steps [] :section-names []})))
```

Replace `parse-json-ld` (lines 190–202) with:

```clojure
(defn parse-json-ld
  "Extract verbatim recipe facts from JSON-LD, or nil if no Recipe found.
  Facts, not a draft: `scrape` decides how facts become sections."
  [html]
  (when-let [node (find-recipe-node (ld-json-blocks html))]
    (let [{:keys [steps section-names]} (parse-instructions (:recipeInstructions node))]
      {:title             (:name node)
       :ingredients       (vec (:recipeIngredient node))
       :steps             steps
       :section-names     section-names
       :servings          (some-> (first-or-self (:recipeYield node)) str)
       :prep-time-minutes (iso-duration->minutes (:prepTime node))
       :cook-time-minutes (iso-duration->minutes (:cookTime node))
       :suggested-labels  (->suggested-labels node)})))

(defn- single-section
  [{:keys [ingredients steps]}]
  [{:name nil :ingredients ingredients :steps steps}])

(defn- facts->result
  [{:keys [title servings prep-time-minutes cook-time-minutes suggested-labels]}
   sections extraction-method warnings]
  {:recipe            {:title             title
                       :sections          sections
                       :servings          servings
                       :prep-time-minutes prep-time-minutes
                       :cook-time-minutes cook-time-minutes}
   :suggested-labels  suggested-labels
   :extraction-method extraction-method
   :warnings          warnings})
```

Replace the body of `scrape` (lines 250–257) with:

```clojure
  [{:keys [api-key fetcher]} url]
  (log/infof "Scraping recipe from %s" url)
  (let [html (fetch-html fetcher url)]
    (if-let [facts (parse-json-ld html)]
      (facts->result facts (single-section facts) "json-ld" [])
      (if api-key
        (extract-with-llm api-key html)
        (throw (ex-info "No recipe found and no LLM available"
                        {:type :scrape :reason :no-recipe-found}))))))
```

- [ ] **Step 4: Run to verify the scraper namespace passes**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`
Expected: PASS (including untouched SSRF/bot-block tests; `llm-fallback-invoked-test` still passes because `extract-with-llm` is unchanged until Task 4).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "feat(scraper): JSON-LD extraction returns verbatim facts; steps as data

- parse-instructions keeps HowToStep text + HowToSection names; instructions->html deleted
- parse-json-ld returns facts; scrape assembles one unnamed section for unsectioned pages

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: Section signals + constrained LLM grouping

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj` (add signals + grouping between the JSON-LD section and the LLM-fallback section; extend `scrape`)
- Test: `test/kaleidoscope/api/recipe_scraper_test.clj`

**Interfaces:**
- Consumes: facts shape, `facts->result`, `single-section` from Task 2; `llm/post-anthropic-sync` + `llm/extract-json` (existing); `fallback-model` (existing).
- Produces: private `sectioned? [facts] → boolean`; private `group-sections-with-llm [api-key facts] → sections-vector|nil`; `scrape` emitting `extraction-method "json-ld+llm-sections"` on success and a single section + warning on any grouping failure. Nothing later depends on new names — this task completes the JSON-LD path.

- [ ] **Step 1: Write the failing tests**

Append to `test/kaleidoscope/api/recipe_scraper_test.clj`:

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section signals + LLM grouping
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def sectioned-json-ld-html
  "<script type='application/ld+json'>
   {\"@type\":\"Recipe\",\"name\":\"Layer Cake\",
    \"recipeIngredient\":[\"2 cups flour\",\"1 cup sugar\",\"1 cup butter\"],
    \"recipeInstructions\":[
      {\"@type\":\"HowToSection\",\"name\":\"Cake\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"Mix\"},{\"@type\":\"HowToStep\",\"text\":\"Bake\"}]},
      {\"@type\":\"HowToSection\",\"name\":\"Frosting\",\"itemListElement\":[{\"@type\":\"HowToStep\",\"text\":\"Whip\"}]}]}
   </script>")

(def valid-grouping-json
  "{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[0,1],\"steps\":[0,1]},{\"name\":\"Frosting\",\"ingredients\":[2],\"steps\":[2]}]}")

(deftest sectioned-scrape-groups-with-llm-test
  (testing "HowToSections route through the grouping call; text is preserved verbatim by index"
    (with-redefs [scraper/fetch-direct    (fn [_] sectioned-json-ld-html)
                  llm/post-anthropic-sync (fn [_ _] {:content [{:text valid-grouping-json}]})]
      (is (match? {:recipe {:title    "Layer Cake"
                            :sections [{:name "Cake"     :ingredients ["2 cups flour" "1 cup sugar"] :steps ["Mix" "Bake"]}
                                       {:name "Frosting" :ingredients ["1 cup butter"]               :steps ["Whip"]}]}
                   :extraction-method "json-ld+llm-sections"
                   :warnings          []}
                  (scraper/scrape {:api-key "sk-test"} public-url))))))

(deftest invalid-grouping-falls-back-test
  (testing "a grouping that is not a partition (missing/duplicate/out-of-range index, bad JSON) flattens with a warning"
    (doseq [bad ["{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[0,1],\"steps\":[0,1]}]}"          ;; missing step+ingredient
                 "{\"sections\":[{\"name\":\"A\",\"ingredients\":[0,1,2,2],\"steps\":[0,1,2]}]}"       ;; duplicate ingredient
                 "{\"sections\":[{\"name\":\"A\",\"ingredients\":[0,1,2],\"steps\":[0,1,2,99]}]}"      ;; out of range
                 "not json at all"]]
      (with-redefs [scraper/fetch-direct    (fn [_] sectioned-json-ld-html)
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text bad}]})]
        (is (match? {:recipe            {:sections [{:name        nil?
                                                     :ingredients ["2 cups flour" "1 cup sugar" "1 cup butter"]
                                                     :steps       ["Mix" "Bake" "Whip"]}]}
                     :extraction-method "json-ld"
                     :warnings          [#"grouping failed"]}
                    (scraper/scrape {:api-key "sk-test"} public-url)))))))

(deftest header-ingredient-lines-trigger-grouping-test
  (testing "header-shaped ingredient lines are a sectioning signal even with flat instructions,
            and header lines are consumed as names, not ingredients"
    (let [html "<script type='application/ld+json'>{\"@type\":\"Recipe\",\"name\":\"Cake\",\"recipeIngredient\":[\"For the cake:\",\"2 cups flour\",\"For the frosting:\",\"1 cup butter\"],\"recipeInstructions\":[{\"@type\":\"HowToStep\",\"text\":\"Mix\"},{\"@type\":\"HowToStep\",\"text\":\"Whip\"}]}</script>"
          grouping "{\"sections\":[{\"name\":\"Cake\",\"ingredients\":[1],\"steps\":[0]},{\"name\":\"Frosting\",\"ingredients\":[3],\"steps\":[1]}]}"]
      (with-redefs [scraper/fetch-direct    (fn [_] html)
                    llm/post-anthropic-sync (fn [_ _] {:content [{:text grouping}]})]
        (is (match? {:recipe {:sections [{:name "Cake"     :ingredients ["2 cups flour"]  :steps ["Mix"]}
                                         {:name "Frosting" :ingredients ["1 cup butter"] :steps ["Whip"]}]}
                     :extraction-method "json-ld+llm-sections"}
                    (scraper/scrape {:api-key "sk-test"} public-url)))))))

(deftest sectioned-without-api-key-flattens-with-warning-test
  (with-redefs [scraper/fetch-direct (fn [_] sectioned-json-ld-html)]
    (is (match? {:recipe            {:sections [{:name nil?}]}
                 :extraction-method "json-ld"
                 :warnings          [#"no LLM"]}
                (scraper/scrape {:api-key nil} public-url)))))
```

- [ ] **Step 2: Run to verify the new tests fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`
Expected: FAIL — the four new tests get `"json-ld"` single-section results (no grouping exists yet); `sectioned-scrape-groups-with-llm-test` and `header-…-test` mismatch on `:extraction-method`.

- [ ] **Step 3: Implement signals + grouping**

In `src/kaleidoscope/api/recipe_scraper.clj`, insert after `facts->result` (before the `;; LLM fallback` divider):

```clojure
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Section signals + LLM grouping
;;
;; The LLM never rewrites content: it returns section names plus indexes into
;; the verbatim ingredient/step lists, the merge is deterministic, and the
;; grouping is mechanically validated. See DESIGN.md §3.
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def ^:private header-line-re
  ;; "For the frosting:", "Cake:" — short label lines sites embed in
  ;; recipeIngredient because JSON-LD cannot express ingredient sections.
  ;; A false positive only costs one unnecessary grouping call.
  #"(?i)\s*(for\s+the\s+.{1,60}|[^,;]{1,60}:)\s*")

(defn- header-like?
  [line]
  (boolean (re-matches header-line-re (or line ""))))

(defn- sectioned?
  [{:keys [section-names ingredients]}]
  (boolean (or (seq section-names)
               (some header-like? ingredients))))

(def ^:private grouping-prompt
  "You are given a recipe's ingredient lines and instruction steps as numbered lists, and possibly candidate section names. Group them into the recipe's components (e.g. cake vs frosting). Return ONLY strict JSON: {\"sections\": [{\"name\": string or null, \"ingredients\": [ingredient indexes], \"steps\": [step indexes]}]}. Rules: use only the given indexes; never rewrite text; every step index appears in exactly one section; every ingredient index appears in exactly one section EXCEPT lines that are section headers (like \"For the frosting:\") — omit header indexes entirely; if the recipe has no real components, return one section with name null containing all indexes.")

(defn- valid-grouping?
  "Steps must be an exact partition of the step indexes; ingredient indexes
  must be in-range and unique, and every non-header ingredient line must be
  assigned (headers may be omitted — they become names, not ingredients)."
  [{:keys [ingredients steps]} sections]
  (let [ing-idxs  (vec (mapcat :ingredients sections))
        step-idxs (vec (mapcat :steps sections))
        required  (set (keep-indexed (fn [i line] (when-not (header-like? line) i))
                                     ingredients))]
    (and (sequential? sections)
         (seq sections)
         (every? #(and (int? %) (<= 0 %) (< % (count ingredients))) ing-idxs)
         (= (count ing-idxs) (count (set ing-idxs)))
         (every? (set ing-idxs) required)
         (every? int? step-idxs)
         (= (sort step-idxs) (vec (range (count steps)))))))

(defn- grouping->sections
  [{:keys [ingredients steps]} sections]
  (mapv (fn [{:keys [name] :as s}]
          {:name        (when-not (str/blank? (or name "")) name)
           :ingredients (mapv ingredients (:ingredients s))
           :steps       (mapv steps (:steps s))})
        sections))

(defn- numbered
  [lines]
  (str/join "\n" (map-indexed (fn [i l] (str i ". " l)) lines)))

(defn- group-sections-with-llm
  "Ask for a grouping and merge it deterministically. Returns a sections
  vector, or nil when the response is unusable — the caller falls back."
  [api-key {:keys [ingredients steps section-names] :as facts}]
  (try
    (let [user     (str "INGREDIENTS:\n" (numbered ingredients)
                        "\n\nSTEPS:\n" (numbered steps)
                        (when (seq section-names)
                          (str "\n\nCANDIDATE SECTION NAMES:\n"
                               (str/join "\n" section-names))))
          response (llm/post-anthropic-sync
                    api-key
                    {:model      fallback-model
                     :max_tokens 1024
                     :system     grouping-prompt
                     :messages   [{:role "user" :content user}]})
          parsed   (json/decode (llm/extract-json (-> response :content first :text)) true)]
      (when (valid-grouping? facts (:sections parsed))
        (grouping->sections facts (:sections parsed))))
    (catch Exception e
      (log/warnf "Section grouping failed: %s" (ex-message e))
      nil)))
```

Replace the `if-let` branch of `scrape` with:

```clojure
    (if-let [facts (parse-json-ld html)]
      (if (sectioned? facts)
        (or (when api-key
              (when-let [sections (group-sections-with-llm api-key facts)]
                (facts->result facts sections "json-ld+llm-sections" [])))
            (facts->result facts (single-section facts) "json-ld"
                           [(if api-key
                              "Sectioned recipe but grouping failed; flattened to one section"
                              "Sectioned recipe but no LLM available; flattened to one section")]))
        (facts->result facts (single-section facts) "json-ld" []))
      (if api-key
        (extract-with-llm api-key html)
        (throw (ex-info "No recipe found and no LLM available"
                        {:type :scrape :reason :no-recipe-found}))))
```

- [ ] **Step 4: Run to verify everything passes**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`
Expected: PASS, all tests including Task 2's no-LLM-call assertion (unsectioned pages never reach the grouper).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "feat(scraper): constrained LLM grouping for sectioned scrapes

- Signals: HowToSection names OR header-shaped ingredient lines (JSON-LD can't express ingredient sections)
- LLM returns names + indexes only; partition-validated; deterministic merge preserves text byte-for-byte
- Any grouping failure flattens to one section with a warning

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 4: Full-LLM fallback emits sections directly

**Files:**
- Modify: `src/kaleidoscope/api/recipe_scraper.clj:216-239` (`extract-prompt`, `extract-with-llm`)
- Test: `test/kaleidoscope/api/recipe_scraper_test.clj` (`llm-fallback-invoked-test`)

**Interfaces:**
- Consumes: `RecipeSection` shape from Task 1.
- Produces: `extract-with-llm` returning a `ScrapeResult` whose `:recipe` validates against the sectioned `RecipeContent` (guarding the `{:min 1}` sections constraint).

- [ ] **Step 1: Update the fallback test to the sectioned payload**

Replace `llm-fallback-invoked-test` (lines 89–97) with:

```clojure
(deftest llm-fallback-invoked-test
  (testing "when JSON-LD is absent and an api-key is present, the LLM path returns sectioned JSON"
    (with-redefs [scraper/fetch-direct (fn [_] "<html><body>Grandma's stew: carrots, beef. Simmer 2 hours.</body></html>")
                  llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Stew\",\"sections\":[{\"name\":null,\"ingredients\":[\"carrots\",\"beef\"],\"steps\":[\"Simmer\"]}],\"servings\":\"4\",\"prep_time_minutes\":10,\"cook_time_minutes\":120,\"suggested_labels\":[\"comfort\"]}"}]})]
      (is (match? {:recipe {:title    "Stew"
                            :sections [{:name nil? :ingredients ["carrots" "beef"] :steps ["Simmer"]}]
                            :cook-time-minutes 120}
                   :suggested-labels  ["comfort"]
                   :extraction-method "llm"}
                  (scraper/scrape {:api-key "sk-test"} "http://example.com/stew"))))))

(deftest llm-fallback-empty-sections-guard-test
  (testing "an LLM response with no sections still satisfies the min-1-section shape"
    (with-redefs [scraper/fetch-direct (fn [_] "<html><body>vague food blog</body></html>")
                  llm/post-anthropic-sync
                  (fn [_ _] {:content [{:text "{\"title\":\"Mystery\",\"sections\":[],\"suggested_labels\":[]}"}]})]
      (is (match? {:recipe {:title "Mystery" :sections [{:ingredients [] :steps []}]}
                   :warnings [#"no sections"]}
                  (scraper/scrape {:api-key "sk-test"} "http://example.com/mystery"))))))
```

- [ ] **Step 2: Run to verify both fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`
Expected: FAIL — `extract-with-llm` still maps `:ingredients`/`:instructions_html`, so `:sections` is missing.

- [ ] **Step 3: Update the prompt and mapping**

Replace `extract-prompt` (lines 216–217) with:

```clojure
(def ^:private extract-prompt
  "Extract the recipe from the page text as strict JSON with keys: title (string), sections (array of {name: string or null, ingredients: array of strings one per ingredient line, steps: array of strings one per instruction step}), servings (string or null), prep_time_minutes (integer or null), cook_time_minutes (integer or null), suggested_labels (array of strings). Use a single section with name null unless the recipe has real components (e.g. cake and frosting). Preserve ingredient lines and step text verbatim. Return ONLY the JSON object, no prose. Strip all blog exposition — keep only the recipe.")
```

In `extract-with-llm`, replace the returned map (lines 231–239) with:

```clojure
    (let [sections (mapv (fn [{:keys [name ingredients steps]}]
                           {:name        name
                            :ingredients (vec ingredients)
                            :steps       (vec steps)})
                         (:sections parsed))
          empty?   (empty? sections)]
      {:recipe {:title             (:title parsed)
                :sections          (if empty?
                                     [{:name nil :ingredients [] :steps []}]
                                     sections)
                :servings          (:servings parsed)
                :prep-time-minutes (:prep_time_minutes parsed)
                :cook-time-minutes (:cook_time_minutes parsed)}
       :suggested-labels  (vec (:suggested_labels parsed))
       :extraction-method "llm"
       :warnings          (if empty? ["LLM returned no sections"] [])})
```

(Keep the existing `let` bindings for `text`/`response`/`raw`/`parsed` above it; only the returned map changes — nest this `let` as the body.)

- [ ] **Step 4: Run the scraper namespace, then the two recipes namespaces**

Run: `./bin/test --focus kaleidoscope.api.recipe-scraper-test`, `./bin/test --focus kaleidoscope.api.recipes-test`, `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: PASS ×3.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_scraper.clj test/kaleidoscope/api/recipe_scraper_test.clj
git commit -m "feat(scraper): full-LLM fallback emits sections with steps, no HTML

- extract-prompt returns sections [{name, ingredients, steps}] as strict JSON
- Empty-sections guard keeps ScrapeResult valid against min-1-section RecipeContent

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: Stale-comment cleanup + full-suite verification

**Files:**
- Modify: `resources/migrations/20260711000001-add-recipes.up.sql:4-12` (comments only — the migration is applied in staging; Migratus tracks ids, not checksums, so a comment edit never re-runs)
- Modify: `plans/2026-07-10-recipes-feature/PLAN.md` (pointer note at top)

**Interfaces:**
- Consumes: nothing — documentation only.
- Produces: nothing later tasks rely on.

- [ ] **Step 1: Fix the migration's shape comments**

In `resources/migrations/20260711000001-add-recipes.up.sql`, replace lines 4–6:

```sql
-- second identity. Recipe content is one JSONB value shape: {title, sections
-- [{name?, ingredients [string], steps [string]}], servings?, times?} (see
-- plans/2026-07-11-recipe-sections/DESIGN.md); `content` is the current recipe
-- and `original_content` is the immutable scrape — same shape, cannot drift.
```

and the two column comments (lines 11–12):

```sql
  content           JSONB NOT NULL,            -- {title, sections[{name?, ingredients[], steps[]}], servings?, prep_time_minutes?, cook_time_minutes?}
  original_content  JSONB,                     -- immutable scrape; same shape as `content`
```

- [ ] **Step 2: Add a pointer note to the base-feature plan**

At the top of `plans/2026-07-10-recipes-feature/PLAN.md` (immediately under the title line), insert:

```markdown
> **Superseded in part:** `RecipeContent` was reshaped to paired sections with
> steps-as-data (no `instructions-html`) before first release — see
> `plans/2026-07-11-recipe-sections/DESIGN.md`. Shape details below are historical.
```

- [ ] **Step 3: Run the full suite**

Run: `task test:summary`
Expected: 0 failures. If anything fails, debug per CLAUDE.md (focus + grep — no full-output dumps).

- [ ] **Step 4: Commit**

```bash
git add resources/migrations/20260711000001-add-recipes.up.sql plans/2026-07-10-recipes-feature/PLAN.md
git commit -m "docs(recipes): update shape comments for sectioned RecipeContent

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Pre-merge checklist (manual, at merge time — not an implementation task)

- [ ] Purge flat-shape dev rows from any persistent staging DB before deploy: `task db:connect ENV=.env.fly.staging` → `DELETE FROM recipes;` (cascades to assignments/audiences). They are Andrew's test scrapes, not user data; ephemeral envs are recreated from scratch anyway. Confirm with Andrew before running the DELETE.
- [ ] `kaleidoscope-ui`: section-aware renderer (headed ingredient lists + `<ol>` per section) and per-step editor — separate repo, separate plan.
