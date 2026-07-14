# Recipe Cook Timeline Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Generate and persist a Gantt-style cook timeline for a recipe — each component becomes a lane, its steps segment into active/passive phases on a real-minute axis, and a deterministic packer schedules them under a single-cook constraint.

**Architecture:** An LLM (pluggable, mock+real like the scorer) segments recipe components into phases with duration estimates and dependencies; a pure packer assigns start times. The result is a durable materialized derived value in a new `recipes.timeline` JSONB column. Regeneration is per-component (steps-hash fingerprint) so hand-nudged durations on unchanged components survive. See `plans/2026-07-14-recipe-cook-timeline/DESIGN.md`.

**Tech Stack:** Clojure, next.jdbc + HoneySQL, Migratus, Malli, Kaocha + matcher-combinators, embedded-postgres / embedded-h2, Anthropic Messages API (java.net.http).

## Global Constraints

- **3-layer separation:** `http_api/` (HTTP only) → `api/` (domain, no HTTP/SQL beyond rdbms helpers) → persistence via `rdbms`. Never cross layers.
- **All schema changes require a migration file** (numbered `.up.sql`/`.down.sql` pair). Never alter tables directly.
- **Every feature ships with automated tests.** Pure logic → unit; endpoints → e2e.
- **`!` suffix on side-effecting functions** (`save-timeline!`, `generate!`).
- **SQL columns are `snake_case`; Clojure is `kebab-case`** — conversion is automatic via camel-snake-kebab; write kebab-case in Clojure.
- **Malli validation at boundaries** (HTTP input, LLM output before persist).
- **Anthropic model:** default `"claude-opus-4-6"` (matches every other LLM call in the codebase); make it a `def` so it's configurable.
- **Recipe JSONB tests run on embedded-postgres** where Postgres JSONB semantics matter; timeline logic is pure and DB-agnostic, and the timeline column works on embedded-h2 (used by the HTTP tests).
- **Phase identity is `"{component-id}/{label}"`.** `component-id` is a section's `:name`, or `"Section N"` (1-based) when unnamed. Labels are unique within a component. Deps reference phases by this id.

---

## File structure

| File | Responsibility |
|---|---|
| `resources/migrations/20260714000001-add-recipe-timeline.{up,down}.sql` | Add/drop nullable `recipes.timeline` JSONB column |
| `src/kaleidoscope/models/recipes.cljc` (modify) | Add `Phase`, `TimelineComponent`, `Override`, `Timeline`; add optional `:timeline` to `GetRecipeResponse`; add `TimelineOverridesRequest` |
| `src/kaleidoscope/api/recipe_timeline.clj` (create) | Pure domain: `component-id`, `steps-hash`, `content-fingerprint`, `changed-ids`, `pack`, `resolve-deps`, `assemble`, `surviving-overrides`, `with-overrides`, and `generate!` orchestration |
| `src/kaleidoscope/timeline/protocol.clj` (create) | `ITimelineGenerator` protocol (`segment`) |
| `src/kaleidoscope/timeline/mock.clj` (create) | Deterministic mock generator (tests + local) |
| `src/kaleidoscope/timeline/llm_generator.clj` (create) | Anthropic-backed generator + response parsing |
| `src/kaleidoscope/api/recipes.clj` (modify) | Parse `:timeline` on read; add `save-timeline!` |
| `src/kaleidoscope/init/env.clj` (modify) | Wire `:timeline-generator` boot instructions + system-map key |
| `src/kaleidoscope/http_api/recipes.clj` (modify) | `POST`/`PUT /recipes/:recipe-url/timeline` (writer-only) |
| `test/kaleidoscope/api/recipe_timeline_test.clj` (create) | Unit tests: packer, fingerprint/merge, generate! |
| `test/kaleidoscope/timeline/mock_test.clj` (create) | Mock generator unit tests |
| `test/kaleidoscope/timeline/llm_generator_test.clj` (create) | LLM response-parsing unit tests |
| `test/kaleidoscope/http_api/recipes_test.clj` (modify) | e2e: generate, per-component regen, override re-pack, auth, failure |

---

## Task 1: Migration — add `recipes.timeline` column

**Files:**
- Create: `resources/migrations/20260714000001-add-recipe-timeline.up.sql`
- Create: `resources/migrations/20260714000001-add-recipe-timeline.down.sql`

**Interfaces:**
- Produces: a nullable `timeline JSONB` column on `recipes` (NULL = not yet generated).

- [ ] **Step 1: Write the up migration**

Create `resources/migrations/20260714000001-add-recipe-timeline.up.sql`:

```sql
-- Cook timeline: a durable materialized derived value computed from a recipe's
-- content + authored duration overrides. NULL until first generated. Shape:
-- {version, generator_version, generated_at, total_minutes,
--  overrides [{phase, minutes}],
--  components [{name, steps_hash, phases [{id, label, kind, steps, estimate, deps, start}]}]}
-- See plans/2026-07-14-recipe-cook-timeline/DESIGN.md.
ALTER TABLE recipes ADD COLUMN timeline JSONB;
```

- [ ] **Step 2: Write the down migration**

Create `resources/migrations/20260714000001-add-recipe-timeline.down.sql`:

```sql
ALTER TABLE recipes DROP COLUMN timeline;
```

- [ ] **Step 3: Run the migration**

Run: `task db:migrate`
Expected: applies `20260714000001-add-recipe-timeline` with no error.

- [ ] **Step 4: Verify the column exists**

Run: `task db:connect` then `\d recipes` (or `SELECT timeline FROM recipes LIMIT 1;`)
Expected: a `timeline` column of type `jsonb`, nullable.

- [ ] **Step 5: Commit**

```bash
git add resources/migrations/20260714000001-add-recipe-timeline.up.sql resources/migrations/20260714000001-add-recipe-timeline.down.sql
git commit -m "feat(recipes): add nullable timeline JSONB column"
```

---

## Task 2: Malli models

**Files:**
- Modify: `src/kaleidoscope/models/recipes.cljc`

**Interfaces:**
- Produces: `Phase`, `TimelineComponent`, `Override`, `Timeline`, `TimelineOverridesRequest`; `GetRecipeResponse` gains optional `:timeline`.

- [ ] **Step 1: Add the timeline schemas**

In `src/kaleidoscope/models/recipes.cljc`, after `RecipeContent` (around line 19), add:

```clojure
;; ---- Cook timeline (derived; see recipe-cook-timeline DESIGN.md) ----

(def Phase
  [:map
   [:id       :string]                 ;; "{component-id}/{label}"
   [:label    :string]                 ;; unique within its component
   [:kind     [:enum "active" "passive"]]
   [:steps    [:sequential :int]]      ;; indices into the component's steps
   [:estimate :int]                    ;; LLM minutes
   [:deps     [:sequential :string]]   ;; phase ids this phase waits on
   [:start    {:optional true} [:maybe :int]]]) ;; packer output (minutes from t0)

(def TimelineComponent
  [:map
   [:name       :string]               ;; the component-id (lane label)
   [:steps-hash :string]
   [:phases     [:sequential Phase]]])

(def Override
  [:map
   [:phase   :string]                  ;; a Phase :id
   [:minutes :int]])

(def Timeline
  [:map
   [:version           :int]
   [:generator-version :int]
   [:generated-at      some?]
   [:total-minutes     :int]
   [:overrides         [:sequential Override]]
   [:components        [:sequential TimelineComponent]]])

(def TimelineOverridesRequest
  [:map [:overrides [:sequential Override]]])
```

- [ ] **Step 2: Add `:timeline` to `GetRecipeResponse`**

In the `GetRecipeResponse` map (around line 60), add this entry before the closing bracket:

```clojure
   [:timeline {:optional true} [:maybe Timeline]]
```

- [ ] **Step 3: Verify it loads**

Run: `clojure -M -e "(require 'kaleidoscope.models.recipes :reload)"`
Expected: no error.

- [ ] **Step 4: Commit**

```bash
git add src/kaleidoscope/models/recipes.cljc
git commit -m "feat(recipes): add Malli schemas for cook timeline"
```

---

## Task 3: The packer (pure)

**Files:**
- Create: `src/kaleidoscope/api/recipe_timeline.clj`
- Test: `test/kaleidoscope/api/recipe_timeline_test.clj`

**Interfaces:**
- Produces:
  - `(pack components overrides)` → `{:components <components with :start filled on each phase> :total-minutes int}`. `components` is `[{:name :steps-hash :phases [{:id :label :kind :steps :estimate :deps}]}]`; `overrides` is `[{:phase :minutes}]`. Active phases serialize on one cook; passive phases float as early as deps allow; effective duration = override-for-id or `:estimate`. Missing deps are ignored. Cycles are broken deterministically.

- [ ] **Step 1: Write the failing tests**

Create `test/kaleidoscope/api/recipe_timeline_test.clj`:

```clojure
(ns kaleidoscope.api.recipe-timeline-test
  (:require [kaleidoscope.api.recipe-timeline :as tl]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(defn- comp1
  "One component with the given phases (each: [id kind estimate deps])."
  [phases]
  [{:name "C" :steps-hash "h"
    :phases (mapv (fn [[id kind est deps]]
                    {:id id :label id :kind kind :steps [] :estimate est :deps deps})
                  phases)}])

(defn- starts [{:keys [components]}]
  (into {} (for [c components p (:phases c)] [(:id p) (:start p)])))

(deftest pack-serializes-active-phases-test
  (testing "two independent active phases never overlap (single cook)"
    (let [packed (tl/pack (comp1 [["a" "active" 10 []] ["b" "active" 5 []]]) [])]
      (is (match? {"a" 0 "b" 10} (starts packed)))
      (is (= 15 (:total-minutes packed))))))

(deftest pack-floats-passive-phases-test
  (testing "a passive phase starts at 0 and overlaps active work"
    (let [packed (tl/pack (comp1 [["marinate" "passive" 24 []]
                                   ["a" "active" 10 []]
                                   ["b" "active" 5 []]]) [])]
      (is (match? {"marinate" 0 "a" 0 "b" 10} (starts packed)))
      (is (= 24 (:total-minutes packed))))))

(deftest pack-respects-dependencies-test
  (testing "a phase can't start before its dep finishes"
    (let [packed (tl/pack (comp1 [["marinate" "passive" 24 []]
                                   ["sear" "active" 10 ["C/marinate"]]]) [])]
      (is (match? {"marinate" 0 "sear" 24} (starts packed)))
      (is (= 34 (:total-minutes packed))))))

(deftest pack-applies-override-test
  (testing "override replaces the estimate for that phase"
    (let [packed (tl/pack (comp1 [["a" "active" 10 []] ["b" "active" 5 []]])
                          [{:phase "a" :minutes 4}])]
      (is (match? {"a" 0 "b" 4} (starts packed)))
      (is (= 9 (:total-minutes packed))))))

(deftest pack-tolerates-dangling-dep-test
  (testing "a dep on a nonexistent phase is ignored, not fatal"
    (let [packed (tl/pack (comp1 [["a" "active" 10 ["C/ghost"]]]) [])]
      (is (match? {"a" 0} (starts packed))))))

(deftest pack-breaks-cycles-test
  (testing "a dependency cycle does not hang; every phase gets a start"
    (let [packed (tl/pack (comp1 [["a" "active" 3 ["C/b"]]
                                   ["b" "active" 3 ["C/a"]]]) [])]
      (is (every? some? (vals (starts packed)))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-timeline-test`
Expected: FAIL — `No namespace: kaleidoscope.api.recipe-timeline`.

- [ ] **Step 3: Implement the packer**

Create `src/kaleidoscope/api/recipe_timeline.clj`:

```clojure
(ns kaleidoscope.api.recipe-timeline
  "Cook-timeline domain logic: pure scheduling + fingerprint/merge, plus the
  `generate!` orchestration over a pluggable timeline generator. No HTTP; no SQL
  beyond what callers pass in. See plans/2026-07-14-recipe-cook-timeline/DESIGN.md.")

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
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-timeline-test`
Expected: PASS (6 tests).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_timeline.clj test/kaleidoscope/api/recipe_timeline_test.clj
git commit -m "feat(recipes): pure cook-timeline packer"
```

---

## Task 4: Fingerprint + merge helpers (pure)

**Files:**
- Modify: `src/kaleidoscope/api/recipe_timeline.clj`
- Test: `test/kaleidoscope/api/recipe_timeline_test.clj`

**Interfaces:**
- Consumes: `pack` (Task 3).
- Produces:
  - `(component-id section index)` → string (`:name` or `"Section N"`).
  - `(steps-hash steps)` → hex sha-256 string of the component's steps.
  - `(content-fingerprint content)` → `[{:id :steps-hash}]` in section order.
  - `(changed-ids content stored)` → set of component-ids whose steps changed (all when `stored` nil / has no components).
  - `(resolve-deps components)` → components with each phase's `:deps` filtered to ids that exist.
  - `(assemble content proposal stored changed)` → components `[{:name :steps-hash :phases}]`: cached phases for unchanged components, `proposal` phases for changed ones; `:steps-hash` refreshed from `content`.
  - `(surviving-overrides stored changed)` → overrides whose component is not in `changed`.
  - `(with-overrides timeline overrides)` → timeline with `:overrides` replaced and re-packed.

- [ ] **Step 1: Write the failing tests**

Append to `test/kaleidoscope/api/recipe_timeline_test.clj`:

```clojure
(def content-a
  {:title "R"
   :sections [{:name "Salmon" :ingredients [] :steps ["marinate" "sear"]}
              {:name "Rice"   :ingredients [] :steps ["rinse" "simmer"]}]})

(deftest content-fingerprint-test
  (testing "one entry per section, id from name, stable hash"
    (let [fp (tl/content-fingerprint content-a)]
      (is (= ["Salmon" "Rice"] (mapv :id fp)))
      (is (= (tl/steps-hash ["marinate" "sear"]) (:steps-hash (first fp)))))))

(deftest component-id-falls-back-to-ordinal-test
  (is (= "Section 1" (tl/component-id {:name nil :steps []} 0)))
  (is (= "Salmon" (tl/component-id {:name "Salmon" :steps []} 0))))

(deftest changed-ids-test
  (let [stored {:components [{:name "Salmon" :steps-hash (tl/steps-hash ["marinate" "sear"]) :phases []}
                            {:name "Rice"   :steps-hash (tl/steps-hash ["rinse" "simmer"]) :phases []}]}
        edited (assoc-in content-a [:sections 0 :steps] ["marinate" "sear" "rest"])]
    (testing "nothing changed" (is (= #{} (tl/changed-ids content-a stored))))
    (testing "one component's steps changed" (is (= #{"Salmon"} (tl/changed-ids edited stored))))
    (testing "no stored timeline ⇒ everything is changed"
      (is (= #{"Salmon" "Rice"} (tl/changed-ids content-a nil))))))

(deftest resolve-deps-drops-unknown-test
  (let [comps [{:name "C" :steps-hash "h"
                :phases [{:id "C/a" :label "a" :kind "active" :steps [] :estimate 1 :deps ["C/ghost" "C/b"]}
                         {:id "C/b" :label "b" :kind "active" :steps [] :estimate 1 :deps []}]}]]
    (is (= ["C/b"] (-> (tl/resolve-deps comps) first :phases first :deps)))))

(deftest surviving-overrides-test
  (let [stored {:overrides [{:phase "Salmon/Sear" :minutes 12}
                            {:phase "Rice/Simmer" :minutes 20}]}]
    (is (= [{:phase "Rice/Simmer" :minutes 20}]
           (tl/surviving-overrides stored #{"Salmon"})))))

(deftest with-overrides-repacks-test
  (let [tline {:version 1 :generator-version 1 :generated-at "t" :total-minutes 15
               :overrides []
               :components [{:name "C" :steps-hash "h"
                             :phases [{:id "C/a" :label "a" :kind "active" :steps [] :estimate 10 :deps []}
                                      {:id "C/b" :label "b" :kind "active" :steps [] :estimate 5 :deps []}]}]}
        out   (tl/with-overrides tline [{:phase "C/a" :minutes 4}])]
    (is (= [{:phase "C/a" :minutes 4}] (:overrides out)))
    (is (= 9 (:total-minutes out)))))
```

- [ ] **Step 2: Run to verify they fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-timeline-test`
Expected: FAIL — `Unable to resolve: tl/content-fingerprint` (and siblings).

- [ ] **Step 3: Implement the helpers**

Add to `src/kaleidoscope/api/recipe_timeline.clj`. Add these to the `:require` at the top by rewriting the `ns` form:

```clojure
(ns kaleidoscope.api.recipe-timeline
  "Cook-timeline domain logic: pure scheduling + fingerprint/merge, plus the
  `generate!` orchestration over a pluggable timeline generator. No HTTP; no SQL
  beyond what callers pass in. See plans/2026-07-14-recipe-cook-timeline/DESIGN.md."
  (:require [clojure.string :as str])
  (:import [java.security MessageDigest]))
```

Then append below `pack`:

```clojure
(defn component-id
  "A component's stable id (lane label): its :name, else 1-based ordinal."
  [section index]
  (or (not-empty (:name section)) (str "Section " (inc index))))

(defn steps-hash
  "Hex SHA-256 of a component's steps — the content fingerprint that decides
  whether a component must be re-segmented."
  [steps]
  (let [md (MessageDigest/getInstance "SHA-256")]
    (->> (str/join " " steps)
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
```

- [ ] **Step 4: Run to verify they pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-timeline-test`
Expected: PASS (all tests, new + Task 3).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_timeline.clj test/kaleidoscope/api/recipe_timeline_test.clj
git commit -m "feat(recipes): timeline fingerprint + merge helpers"
```

---

## Task 5: Generator protocol + mock

**Files:**
- Create: `src/kaleidoscope/timeline/protocol.clj`
- Create: `src/kaleidoscope/timeline/mock.clj`
- Test: `test/kaleidoscope/timeline/mock_test.clj`

**Interfaces:**
- Produces:
  - Protocol `kaleidoscope.timeline.protocol/ITimelineGenerator` with `(segment [this recipe changed-ids cached])` → `{:components [{:name string :phases [{:id :label :kind :steps :estimate :deps}]}]}`. `recipe` is `{:content <RecipeContent>}`; `changed-ids` a set; `cached` the stored components (so a real generator can reference unchanged phases when emitting deps). A generator MUST emit phases for changed components; it may emit them for all.
  - `kaleidoscope.timeline.mock/make-mock-generator` → an `ITimelineGenerator`.

Mock rules (deterministic, so tests assert exact schedules): one phase per component covering all its steps; `label` = component-id; `id` = `"{component-id}/{component-id}"`; `estimate` = `2 + 3 * (step count)`; `kind` = `"passive"` if any step matches the passive cue regex, else `"active"`; `deps` = the last component depends on the single phase of every earlier component (a stand-in plate/assembly step), all others depend on nothing.

- [ ] **Step 1: Write the failing test**

Create `test/kaleidoscope/timeline/mock_test.clj`:

```clojure
(ns kaleidoscope.timeline.mock-test
  (:require [kaleidoscope.timeline.mock :as mock]
            [kaleidoscope.timeline.protocol :as protocol]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match?]]))

(def recipe
  {:content {:title "R"
             :sections [{:name "Salmon" :ingredients [] :steps ["marinate fish" "sear"]}
                        {:name "Plate"  :ingredients [] :steps ["assemble"]}]}})

(deftest mock-segments-each-component-test
  (let [{:keys [components]} (protocol/segment (mock/make-mock-generator) recipe #{"Salmon" "Plate"} nil)]
    (testing "one phase per component"
      (is (= ["Salmon" "Plate"] (mapv :name components)))
      (is (every? #(= 1 (count (:phases %))) components)))
    (testing "passive cue classifies, estimate scales with step count, last depends on earlier"
      (is (match? {:id "Salmon/Salmon" :kind "passive" :estimate 8 :deps []}
                  (-> components first :phases first)))
      (is (match? {:id "Plate/Plate" :kind "active" :estimate 5 :deps ["Salmon/Salmon"]}
                  (-> components second :phases first))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.timeline.mock-test`
Expected: FAIL — `No namespace: kaleidoscope.timeline.mock`.

- [ ] **Step 3: Implement the protocol**

Create `src/kaleidoscope/timeline/protocol.clj`:

```clojure
(ns kaleidoscope.timeline.protocol)

(defprotocol ITimelineGenerator
  (segment [this recipe changed-ids cached]
    "Segment a recipe's components into phases.

     recipe:      {:content <RecipeContent>}
     changed-ids: #{component-id …} — components whose steps changed
     cached:      stored [{:name :phases …}] for dependency reference (may be nil)

     Returns {:components [{:name    component-id
                            :phases  [{:id     \"{component-id}/{label}\"
                                       :label  str    ;; unique within component
                                       :kind   \"active\"|\"passive\"
                                       :steps  [int]   ;; indices into the component's steps
                                       :estimate int   ;; minutes
                                       :deps   [phase-id …]}]}]}
     Must include changed components; may include all."))
```

- [ ] **Step 4: Implement the mock**

Create `src/kaleidoscope/timeline/mock.clj`:

```clojure
(ns kaleidoscope.timeline.mock
  (:require [clojure.string :as str]
            [kaleidoscope.api.recipe-timeline :as tl]
            [kaleidoscope.timeline.protocol :as protocol]))

(def ^:private passive-cue
  #"(?i)marinate|marinade|rise|proof|chill|rest|refrigerate|soak|bake|roast|simmer|freeze")

(defrecord MockGenerator []
  protocol/ITimelineGenerator
  (segment [_this recipe _changed-ids _cached]
    (let [sections (get-in recipe [:content :sections])
          n        (count sections)]
      {:components
       (vec (map-indexed
             (fn [i section]
               (let [cid   (tl/component-id section i)
                     steps (:steps section)
                     kind  (if (some #(re-find passive-cue %) steps) "passive" "active")
                     ;; last component depends on the single phase of every earlier one
                     deps  (if (= i (dec n))
                             (vec (for [j (range (dec n))]
                                    (let [pid (tl/component-id (nth sections j) j)]
                                      (str pid "/" pid))))
                             [])]
                 {:name cid
                  :phases [{:id       (str cid "/" cid)
                            :label    cid
                            :kind     kind
                            :steps    (vec (range (count steps)))
                            :estimate (+ 2 (* 3 (count steps)))
                            :deps     deps}]}))
             sections))})))

(defn make-mock-generator [] (->MockGenerator))
```

- [ ] **Step 5: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.timeline.mock-test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/kaleidoscope/timeline/protocol.clj src/kaleidoscope/timeline/mock.clj test/kaleidoscope/timeline/mock_test.clj
git commit -m "feat(recipes): timeline generator protocol + mock"
```

---

## Task 6: `generate!` orchestration

**Files:**
- Modify: `src/kaleidoscope/api/recipe_timeline.clj`
- Test: `test/kaleidoscope/api/recipe_timeline_test.clj`

**Interfaces:**
- Consumes: `changed-ids`, `assemble`, `resolve-deps`, `surviving-overrides`, `pack` (Tasks 3–4); `protocol/segment` (Task 5).
- Produces:
  - `(generate! {:keys [generator content stored generator-version now]})` → a full `Timeline` blob. Short-circuits (returns `stored`) when nothing changed and `generator-version` is current. Otherwise calls `segment` for the changed set, assembles under the trust boundary, resolves deps, carries surviving overrides, packs, and stamps `:generated-at`/`:version`/`:generator-version`.
  - `default-generator-version` (int `def`).

- [ ] **Step 1: Write the failing tests**

Append to `test/kaleidoscope/api/recipe_timeline_test.clj`:

```clojure
(require '[kaleidoscope.timeline.mock :as mock])

(deftest generate!-first-time-test
  (let [tline (tl/generate! {:generator (mock/make-mock-generator)
                             :content content-a :stored nil
                             :generator-version 1 :now "t0"})]
    (testing "blob shape + packed"
      (is (match? {:version 1 :generator-version 1 :generated-at "t0" :overrides []}
                  tline))
      (is (= ["Salmon" "Rice"] (mapv :name (:components tline))))
      (is (every? (fn [c] (every? #(some? (:start %)) (:phases c))) (:components tline))))))

(deftest generate!-short-circuits-when-unchanged-test
  (let [stored (tl/generate! {:generator (mock/make-mock-generator)
                              :content content-a :stored nil
                              :generator-version 1 :now "t0"})]
    (testing "no changes + current version ⇒ returns stored untouched (no regen)"
      (is (identical? stored (tl/generate! {:generator (reify kaleidoscope.timeline.protocol/ITimelineGenerator
                                                         (segment [_ _ _ _] (throw (ex-info "should not run" {}))))
                                            :content content-a :stored stored
                                            :generator-version 1 :now "t1"}))))))

(deftest generate!-keeps-override-on-unchanged-component-test
  (let [gen    (mock/make-mock-generator)
        base   (tl/generate! {:generator gen :content content-a :stored nil
                              :generator-version 1 :now "t0"})
        nudged (tl/with-overrides base [{:phase "Rice/Rice" :minutes 40}])
        edited (assoc-in content-a [:sections 0 :steps] ["marinate" "sear" "rest"])
        regen  (tl/generate! {:generator gen :content edited :stored nudged
                              :generator-version 1 :now "t2"})]
    (testing "editing Salmon regenerates only Salmon; Rice override survives"
      (is (= [{:phase "Rice/Rice" :minutes 40}] (:overrides regen)))
      ;; Salmon re-estimated: 3 steps ⇒ 2 + 3*3 = 11
      (is (= 11 (->> regen :components (filter #(= "Salmon" (:name %))) first :phases first :estimate))))))
```

- [ ] **Step 2: Run to verify they fail**

Run: `./bin/test --focus kaleidoscope.api.recipe-timeline-test`
Expected: FAIL — `Unable to resolve: tl/generate!`.

- [ ] **Step 3: Implement `generate!`**

Add `[kaleidoscope.timeline.protocol :as protocol]` to the `ns` `:require` in `src/kaleidoscope/api/recipe_timeline.clj`, then append:

```clojure
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
```

- [ ] **Step 4: Run to verify they pass**

Run: `./bin/test --focus kaleidoscope.api.recipe-timeline-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/api/recipe_timeline.clj test/kaleidoscope/api/recipe_timeline_test.clj
git commit -m "feat(recipes): generate! orchestration with per-component regen"
```

---

## Task 7: LLM generator

**Files:**
- Create: `src/kaleidoscope/timeline/llm_generator.clj`
- Test: `test/kaleidoscope/timeline/llm_generator_test.clj`

**Interfaces:**
- Consumes: `ITimelineGenerator` (Task 5).
- Produces:
  - `kaleidoscope.timeline.llm-generator/parse-segment-response` (public for testing) → `{:components …}` from Claude's raw text; strips markdown fences; throws `ex-info {:type :generation}` on malformed JSON.
  - `kaleidoscope.timeline.llm-generator/make-llm-generator` `{:keys [api-key model]}` → an `ITimelineGenerator` that calls Anthropic once and parses the result.

- [ ] **Step 1: Write the failing test (parsing only — no network)**

Create `test/kaleidoscope/timeline/llm_generator_test.clj`:

```clojure
(ns kaleidoscope.timeline.llm-generator-test
  (:require [kaleidoscope.timeline.llm-generator :as llm]
            [clojure.test :refer [deftest is testing]]
            [matcher-combinators.test :refer [match? thrown-match?]]))

(deftest parse-segment-response-test
  (testing "strips fences and returns components"
    (let [text "```json\n{\"components\":[{\"name\":\"Salmon\",\"phases\":[{\"id\":\"Salmon/Marinate\",\"label\":\"Marinate\",\"kind\":\"passive\",\"steps\":[0],\"estimate\":24,\"deps\":[]}]}]}\n```"]
      (is (match? {:components [{:name "Salmon"
                                 :phases [{:id "Salmon/Marinate" :label "Marinate"
                                           :kind "passive" :estimate 24 :deps []}]}]}
                  (llm/parse-segment-response text)))))
  (testing "malformed JSON throws a generation error"
    (is (thrown-match? clojure.lang.ExceptionInfo {:type :generation}
                       (llm/parse-segment-response "not json at all")))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.timeline.llm-generator-test`
Expected: FAIL — `No namespace: kaleidoscope.timeline.llm-generator`.

- [ ] **Step 3: Implement the LLM generator**

Create `src/kaleidoscope/timeline/llm_generator.clj` (mirrors `scoring/llm_scorer.clj`'s Anthropic plumbing; duplication of the small HTTP helper matches the existing scorer/executor pattern):

```clojure
(ns kaleidoscope.timeline.llm-generator
  (:require [cheshire.core :as json]
            [clojure.string :as str]
            [kaleidoscope.timeline.protocol :as protocol]
            [taoensso.timbre :as log])
  (:import [java.net URI]
           [java.net.http HttpClient HttpRequest HttpResponse
            HttpRequest$BodyPublishers HttpResponse$BodyHandlers]
           [java.time Duration]))

(def ^:private anthropic-messages-url "https://api.anthropic.com/v1/messages")
(def ^:private anthropic-version "2023-06-01")
(def ^:private default-model "claude-opus-4-6")
(def ^:private connect-timeout (Duration/ofSeconds 10))
(def ^:private request-timeout (Duration/ofSeconds 60))

(defn- post-anthropic [api-key body-map]
  (let [request (-> (HttpRequest/newBuilder)
                    (.uri (URI/create anthropic-messages-url))
                    (.header "Content-Type" "application/json")
                    (.header "x-api-key" api-key)
                    (.header "anthropic-version" anthropic-version)
                    (.timeout request-timeout)
                    (.POST (HttpRequest$BodyPublishers/ofString (json/encode body-map)))
                    (.build))
        client  (-> (HttpClient/newBuilder) (.connectTimeout connect-timeout) (.build))
        resp    (.send client request (HttpResponse$BodyHandlers/ofString))]
    (when-not (= 200 (.statusCode resp))
      (log/errorf "Anthropic timeline error %d: %s" (.statusCode resp) (.body resp))
      (throw (ex-info "Anthropic API error" {:type :generation :status (.statusCode resp)})))
    (json/decode (.body resp) true)))

(defn parse-segment-response
  "Parse Claude's JSON into {:components …}. Throws ex-info {:type :generation}
  on malformed output."
  [text]
  (let [clean (-> text str/trim
                  (str/replace #"(?s)^```(?:json)?\s*" "")
                  (str/replace #"\s*```$" "") str/trim)]
    (try
      (let [parsed (json/decode clean true)]
        (when-not (sequential? (:components parsed))
          (throw (ex-info "no :components" {:type :generation})))
        parsed)
      (catch com.fasterxml.jackson.core.JsonProcessingException _
        (throw (ex-info "malformed timeline JSON" {:type :generation}))))))

(defn- build-prompt [recipe changed-ids cached]
  (str "You schedule cooking. For each recipe COMPONENT, segment its steps into "
       "phases (a contiguous group of steps with one duration). Output ONLY JSON: "
       "{\"components\":[{\"name\":<component-id>,\"phases\":[{\"id\":\"<component-id>/<label>\","
       "\"label\":<unique-within-component>,\"kind\":\"active\"|\"passive\",\"steps\":[<int step indices>],"
       "\"estimate\":<minutes int>,\"deps\":[\"<phase id>\"]}]}]}. "
       "\"passive\" = unattended (marinate/rise/rest/bake/simmer); deps may cross components. "
       "COMPONENTS MARKED CHANGED must be re-segmented: " (pr-str (vec changed-ids)) ". "
       "For UNCHANGED components reproduce the cached phases exactly. "
       "Cached: " (json/encode cached) "\n\nRecipe: " (json/encode (:content recipe))))

(defrecord LlmGenerator [api-key model]
  protocol/ITimelineGenerator
  (segment [_this recipe changed-ids cached]
    (let [body {:model      (or model default-model)
                :max_tokens 4096
                :messages   [{:role "user" :content (build-prompt recipe changed-ids cached)}]}
          text (-> (post-anthropic api-key body) :content first :text)]
      (parse-segment-response text))))

(defn make-llm-generator [{:keys [api-key model]}]
  (->LlmGenerator api-key model))
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.timeline.llm-generator-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/timeline/llm_generator.clj test/kaleidoscope/timeline/llm_generator_test.clj
git commit -m "feat(recipes): Anthropic-backed timeline generator"
```

---

## Task 8: Persistence — parse + `save-timeline!`

**Files:**
- Modify: `src/kaleidoscope/api/recipes.clj`
- Test: `test/kaleidoscope/api/recipes_test.clj`

**Interfaces:**
- Consumes: `rdbms/scoped-update!`, `get-recipe` (existing).
- Produces:
  - `get-recipe`/`get-recipes` return a decoded `:timeline` map (or nil).
  - `(save-timeline! db hostname recipe-url timeline)` → the recipe with the new `:timeline`; does **not** touch `:modified-at` (the timeline is derived, not an authored edit). Returns nil if no such recipe.

- [ ] **Step 1: Write the failing test**

Append to `test/kaleidoscope/api/recipes_test.clj`:

```clojure
(deftest save-and-read-timeline-test
  (let [db (embedded-pg/fresh-db!)
        _  (recipes/create-recipe! db (example-recipe))
        tl {:version 1 :generator-version 1 :generated-at "t0" :total-minutes 30
            :overrides []
            :components [{:name "Section 1" :steps-hash "h"
                          :phases [{:id "Section 1/cook" :label "cook" :kind "active"
                                    :steps [0 1] :estimate 30 :deps [] :start 0}]}]}]
    (testing "save returns the recipe carrying the decoded timeline"
      (is (match? {:recipe-url "chana-masala" :timeline {:total-minutes 30 :overrides []}}
                  (recipes/save-timeline! db host "chana-masala" tl))))
    (testing "a subsequent read decodes the timeline to a map"
      (is (match? {:timeline {:components [{:name "Section 1"}]}}
                  (recipes/get-recipe db host "chana-masala"))))
    (testing "saving a timeline does not bump modified-at"
      (let [before (:modified-at (recipes/get-recipe db host "chana-masala"))]
        (recipes/save-timeline! db host "chana-masala" (assoc tl :total-minutes 31))
        (is (= before (:modified-at (recipes/get-recipe db host "chana-masala"))))))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.api.recipes-test`
Expected: FAIL — `Unable to resolve: recipes/save-timeline!`.

- [ ] **Step 3: Parse `:timeline` on read**

In `src/kaleidoscope/api/recipes.clj`, extend `parse-content-columns` (around line 30) to also normalize `:timeline`:

```clojure
(defn- parse-content-columns
  [recipe]
  (-> recipe
      (update :content ->content)
      (cond-> (:original-content recipe) (update :original-content ->content))
      (cond-> (:timeline recipe)         (update :timeline ->content))))
```

- [ ] **Step 4: Add `save-timeline!`**

In `src/kaleidoscope/api/recipes.clj`, add after `update-recipe!` (around line 322):

```clojure
(defn save-timeline!
  "Persist a recipe's derived cook timeline (scoped to hostname). Does not touch
  :modified-at — the timeline is derived data, not an authored edit. Returns the
  recipe with the decoded timeline, or nil if no recipe with that slug exists."
  [db hostname recipe-url timeline]
  (when (:id (get-recipe db hostname recipe-url))
    (rdbms/scoped-update! db :recipes {:recipe-url recipe-url :hostname hostname}
                          {:timeline timeline})
    (get-recipe db hostname recipe-url)))
```

- [ ] **Step 5: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.api.recipes-test`
Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/kaleidoscope/api/recipes.clj test/kaleidoscope/api/recipes_test.clj
git commit -m "feat(recipes): persist + decode the timeline column"
```

---

## Task 9: Env wiring — `:timeline-generator`

**Files:**
- Modify: `src/kaleidoscope/init/env.clj`

**Interfaces:**
- Consumes: `mock/make-mock-generator`, `llm-generator/make-llm-generator`.
- Produces: a `:timeline-generator` component in the system map, selected by `KALEIDOSCOPE_TIMELINE_GENERATOR_TYPE` (`mock` default, `llm`).

- [ ] **Step 1: Add the requires**

In `src/kaleidoscope/init/env.clj`, add to the `ns` `:require` (near the other scoring/workflow requires around line 17-19):

```clojure
            [kaleidoscope.timeline.mock :as timeline-mock]
            [kaleidoscope.timeline.llm-generator :as timeline-llm]
```

- [ ] **Step 2: Add the boot instructions**

After `kaleidoscope-image-transcriber-boot-instructions` (around line 294), add:

```clojure
(def kaleidoscope-timeline-generator-boot-instructions
  "Cook-timeline segmentation. `mock` (default) returns deterministic phases for
  local dev / tests. `llm` segments via Claude (needs ANTHROPIC_API_KEY)."
  {:name      :kaleidoscope-timeline-generator
   :path      "KALEIDOSCOPE_TIMELINE_GENERATOR_TYPE"
   :launchers {"mock" (fn [_env] (timeline-mock/make-mock-generator))
               "llm"  (fn [env] (timeline-llm/make-llm-generator
                                 {:api-key (get env "ANTHROPIC_API_KEY")}))}
   :default   "mock"})
```

- [ ] **Step 3: Register it in the boot list and system map**

Add `kaleidoscope-timeline-generator-boot-instructions` to the boot-instructions vector (around line 329, after `kaleidoscope-image-transcriber-boot-instructions`).

In `prepare-kaleidoscope`'s `let` bindings (around line 375) add `kaleidoscope-timeline-generator`, and in the returned system map (around line 389) add:

```clojure
   :timeline-generator      kaleidoscope-timeline-generator
```

- [ ] **Step 4: Verify the system boots**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: existing recipe HTTP tests still PASS (the system map now includes `:timeline-generator`).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/init/env.clj
git commit -m "feat(recipes): wire pluggable timeline-generator component"
```

---

## Task 10: HTTP routes — `POST`/`PUT /recipes/:recipe-url/timeline`

**Files:**
- Modify: `src/kaleidoscope/http_api/recipes.clj`
- Modify: `src/kaleidoscope/api/recipe_timeline.clj` (add `now` helper — optional)

**Interfaces:**
- Consumes: `recipe-timeline/generate!`, `recipe-timeline/with-overrides`, `recipes-api/get-recipe`, `recipes-api/save-timeline!`, `authz/writer?`, `hu/get-host`.
- Produces: two writer-only routes under `/recipes/:recipe-url`.
  - `POST …/timeline` → regenerate from current content, persist, `200 {:timeline …}`. Generator failure → `502 {:reason "generation-failed"}`, recipe untouched. Non-writer → `404`. Missing recipe → `404`.
  - `PUT …/timeline` (body `TimelineOverridesRequest`) → apply overrides, re-pack (no LLM), persist, `200 {:timeline …}`. No timeline yet → `404`. Non-writer → `404`.

- [ ] **Step 1: Write the failing e2e tests**

Append to `test/kaleidoscope/http_api/recipes_test.clj`:

```clojure
(defn create-recipe! [app]
  (app (-> (mock/request :post "https://andrewslai.com/recipes")
           as-writer
           (mock/json-body example-body))))

(deftest timeline-generate-and-override-test
  (let [app (make-app "custom-authenticated-user")]
    (create-recipe! app)
    (testing "POST generates + persists a packed timeline"
      (let [resp (app (-> (mock/request :post "https://andrewslai.com/recipes/chana-masala/timeline")
                          as-writer))]
        (is (match? {:status 200
                     :body {:timeline {:total-minutes pos? :overrides []
                                       :components [{:name "Section 1"}]}}}
                    resp))))
    (testing "GET returns the persisted timeline"
      (is (match? {:status 200 :body {:timeline {:total-minutes pos?}}}
                  (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))
    (testing "PUT overrides re-packs without regenerating"
      (is (match? {:status 200 :body {:timeline {:overrides [{:phase "Section 1/Section 1" :minutes 99}]
                                                 :total-minutes 99}}}
                  (app (-> (mock/request :put "https://andrewslai.com/recipes/chana-masala/timeline")
                           as-writer
                           (mock/json-body {:overrides [{:phase "Section 1/Section 1" :minutes 99}]}))))))))

(deftest timeline-writer-only-test
  (let [app (make-app "always-unauthenticated")]
    (testing "non-writer POST timeline ⇒ 404 (no leak)"
      (is (match? {:status 404}
                  (app (mock/request :post "https://andrewslai.com/recipes/chana-masala/timeline")))))))
```

- [ ] **Step 2: Run to verify they fail**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: FAIL — 404/route-not-found on the timeline paths.

- [ ] **Step 3: Add the routes**

In `src/kaleidoscope/http_api/recipes.clj`, add the require:

```clojure
            [kaleidoscope.api.recipe-timeline :as timeline-api]
```

Add `bad-gateway` to the `ring.util.http-response` `:refer` vector at the top of the file (a generation failure returns 502 per DESIGN):

```clojure
   [ring.util.http-response :refer [bad-gateway bad-request conflict not-found ok unprocessable-entity]]
```

Add this route vector inside `reitit-recipes-routes`, immediately after the `["/:recipe-url/lineage" …]` block (around line 192, before the two closing brackets `]])`):

```clojure
   ["/:recipe-url/timeline"
    {:post {:summary    "Regenerate the cook timeline from current content (writer-only)"
            :responses  (merge hu/openapi-401 hu/openapi-404
                               {200 {:body [:map [:timeline models.recipes/Timeline]]}
                                502 {:body [:map [:reason :string]]}})
            :parameters {:path {:recipe-url :string}}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (if-not (authz/writer? request)
                            (not-found {:reason "Missing"})
                            (let [db        (:database components)
                                  host      (hu/get-host request)
                                  url       (get-in parameters [:path :recipe-url])]
                              (if-let [recipe (recipes-api/get-recipe db host url)]
                                (try
                                  (let [timeline (timeline-api/generate!
                                                  {:generator         (:timeline-generator components)
                                                   :content           (:content recipe)
                                                   :stored            (:timeline recipe)
                                                   :generator-version timeline-api/default-generator-version
                                                   :now               (str (java.time.Instant/now))})]
                                    (ok {:timeline (:timeline (recipes-api/save-timeline! db host url timeline))}))
                                  (catch clojure.lang.ExceptionInfo e
                                    (if (= :generation (:type (ex-data e)))
                                      (bad-gateway {:reason "generation-failed"})
                                      (throw e))))
                                (not-found {:reason "Missing"})))))}
     :put  {:summary    "Apply duration overrides and re-pack (writer-only; no LLM)"
            :responses  (merge hu/openapi-401 hu/openapi-404
                               {200 {:body [:map [:timeline models.recipes/Timeline]]}})
            :parameters {:path {:recipe-url :string}
                         :body models.recipes/TimelineOverridesRequest}
            :handler    (fn [{:keys [components parameters] :as request}]
                          (if-not (authz/writer? request)
                            (not-found {:reason "Missing"})
                            (let [db     (:database components)
                                  host   (hu/get-host request)
                                  url    (get-in parameters [:path :recipe-url])
                                  recipe (recipes-api/get-recipe db host url)]
                              (if-let [stored (:timeline recipe)]
                                (let [updated (timeline-api/with-overrides
                                               stored (get-in parameters [:body :overrides]))]
                                  (ok {:timeline (:timeline (recipes-api/save-timeline! db host url updated))}))
                                (not-found {:reason "Missing"})))))}}]
```

- [ ] **Step 4: Run to verify they pass**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: PASS (new + existing).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/http_api/recipes.clj test/kaleidoscope/http_api/recipes_test.clj
git commit -m "feat(recipes): timeline generate + override HTTP routes"
```

---

## Task 11: Generation-failure e2e coverage

**Files:**
- Modify: `test/kaleidoscope/http_api/recipes_test.clj`

**Interfaces:**
- Consumes: `make-app` with a `KALEIDOSCOPE_TIMELINE_GENERATOR_TYPE` that fails; verifies the recipe survives a failed generation.

- [ ] **Step 1: Write the failing test**

A failing generator can't be selected by env (no such launcher), so inject one by rebinding the component. Add to `test/kaleidoscope/http_api/recipes_test.clj`:

```clojure
(deftest timeline-generation-failure-leaves-recipe-saved-test
  (let [app (make-app "custom-authenticated-user"
                      {"KALEIDOSCOPE_TIMELINE_GENERATOR_TYPE" "mock"})]
    (create-recipe! app)
    (with-redefs [kaleidoscope.api.recipe-timeline/generate!
                  (fn [_] (throw (ex-info "boom" {:type :generation})))]
      (testing "generator failure ⇒ 502, recipe untouched"
        (is (match? {:status 502 :body {:reason "generation-failed"}}
                    (app (-> (mock/request :post "https://andrewslai.com/recipes/chana-masala/timeline")
                             as-writer))))))
    (testing "the recipe itself is still retrievable and un-timelined"
      (is (match? {:status 200 :body {:recipe-url "chana-masala" :timeline nil?}}
                  (app (mock/request :get "https://andrewslai.com/recipes/chana-masala")))))))
```

- [ ] **Step 2: Run to verify it fails, then passes**

Run: `./bin/test --focus kaleidoscope.http-api.recipes-test`
Expected: the two `testing` assertions PASS once the Task 10 handler maps `{:type :generation}` → `502` (this task only adds coverage; if it already passes, that confirms the handler). If the recipe read shows a non-nil timeline, the handler is persisting on failure — fix the handler so `save-timeline!` runs only on success.

- [ ] **Step 3: Commit**

```bash
git add test/kaleidoscope/http_api/recipes_test.clj
git commit -m "test(recipes): timeline generation failure leaves recipe saved"
```

---

## Task 12: Full-suite verification + docs

**Files:**
- Modify: `docs/operations.md` (only if a new env var needs operator documentation)

**Interfaces:** none.

- [ ] **Step 1: Run the full suite**

Run: `task test`
Expected: all tests PASS.

- [ ] **Step 2: Document the new env var**

`KALEIDOSCOPE_TIMELINE_GENERATOR_TYPE` (`mock` default, `llm`) is a new runtime knob. Per CLAUDE.md Sharp Edge #6, if `docs/operations.md` enumerates env vars, add a row for it next to `KALEIDOSCOPE_SCORER_TYPE`. If it doesn't, skip this step.

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "docs(recipes): document timeline-generator env var"
```

---

## Self-review notes (traceability to DESIGN.md)

- **Real-minute axis / packer / single-cook / passive-float** → Task 3.
- **Per-component fingerprint, keep-nudges-on-unchanged, trust boundary** → Tasks 4 & 6 (`assemble` keeps cached phases for unchanged components; `surviving-overrides` drops only changed).
- **Single estimate + nudge/re-pack (no LLM)** → `with-overrides` (Task 4), `PUT` route (Task 10).
- **Only-when-steps-changed regen + generator-version** → `changed-ids` + `generate!` short-circuit (Tasks 4 & 6).
- **Durable materialized derived value in `recipes.timeline`** → Tasks 1 & 8; decoded on read; `:modified-at` untouched.
- **LLM out of the save path; save never fails on the LLM** → separate `POST /timeline` route; failure → 502, recipe untouched (Tasks 10 & 11).
- **Writer-only authoring; timeline visible via GET per recipe visibility** → Task 10 auth + `GetRecipeResponse` (Task 2).
- **Pluggable mock+llm** → Tasks 5, 7, 9.
- **Overrides annotate phases; phase-id stability is load-bearing** → phase id `"{component}/{label}"` (Global Constraints), `assemble` keeps unchanged phases verbatim (Task 4).
- **Out of scope** (frontend Gantt, versioning, ranges, resource lanes) → not planned; consistent with DESIGN.
