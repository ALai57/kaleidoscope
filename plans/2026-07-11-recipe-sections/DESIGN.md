# Recipe Sections — Design (v2)

**Date:** 2026-07-11
**Status:** Proposed (v2 — re-litigated base-feature decisions; supersedes v1)
**Context:** plans/2026-07-10-recipes-feature/PLAN.md (base feature),
plans/2026-07-11-recipe-scrape-fallback (LLM fallback)

## Problem

A baking recipe often has distinct components — e.g. a layer cake with **Cake**
and **Frosting** — each with its own ingredients *and* its own instructions.
Today `RecipeContent` is flat (`ingredients: [string]`, one `instructions-html`
blob), so component structure is lost and the UI cannot pair a component's
ingredients with its steps.

**Root cause (v2 finding):** the flat shape isn't the original sin —
`instructions-html` is. schema.org delivers instructions as *structured steps*
(`HowToStep[]`, optionally grouped in `HowToSection`s), and the scraper's
`instructions->html` destroys that structure at ingestion by joining everything
into an `<ol>` string. Sections were about to be bolted on to recover structure
we throw away on purpose. Since the recipes feature is unreleased and unmerged,
we fix the model instead of accreting around it.

## Decisions

1. **Sections are paired components** (Andrew): each section owns its
   ingredients AND its steps. Not display-only grouping; not reusable
   cross-recipe sub-recipes.
2. **LLM only when the scrape is sectioned** (Andrew): unsectioned recipes —
   the common case — never pay an LLM call.
3. **Steps are data, not HTML** (v2): instructions become `steps: [string]`,
   one plain-text string per step. HTML is a rendering concern that belongs to
   the UI (TipTap), not the domain. `instructions-html` is removed everywhere.
4. **One shape, ever** (v2): the sectioned shape is the only `RecipeContent`
   that ever ships. No read-time normalization, no legacy dual-path SQL. The
   handful of flat dev rows in staging/ephemeral envs are debris, not data —
   purge them before merge (see §7).
5. **LLM groups, never rewrites** (v2): when sectioning is needed, the LLM
   returns section names + *indices into the verbatim extracted lists*, and a
   deterministic merge builds the sections. Extracted ingredient/step text is
   preserved byte-for-byte by construction.

## Approaches considered

- **Sections as canonical shape, steps as data** — CHOSEN (below).
- **v1: sections wrapping `instructions-html`, read-time normalization of
  legacy rows, full LLM re-extraction when sectioned** — rejected on
  re-litigation: kept presentation complected into the domain one level down
  (steps invisible inside HTML); carried a permanent normalizer plus a
  read/query incoherence (SQL filter and normalizer disagreeing about flat
  rows) to protect throwaway dev data; full re-extraction let the LLM rewrite
  quantities it had no business touching, and its `HowToSection`-only trigger
  silently missed pages that section only their ingredients.
- **Optional `sections` overlay next to flat fields** — rejected: dual
  representation of the same information; the drift the "same shape, cannot
  drift" rule exists to prevent.
- **Relational `recipe_sections` table** — rejected: a section has no identity
  — nothing references it independently; rows would manufacture identity where
  only value exists. No per-section query need; every write becomes child-row
  diffing.
- **Structured ingredients (`{quantity, unit, item}`)** — rejected as
  speculative structure: parsing "2 cups flour, sifted" is lossy and scaling is
  out of scope. Freeform strings are the information we actually possess.

## Design

### 1. Model (`src/kaleidoscope/models/recipes.cljc`)

```clojure
(def RecipeSection
  [:map
   [:name        {:optional true} [:maybe :string]] ;; absent/nil ⇒ unnamed
   [:ingredients [:sequential :string]]             ;; verbatim lines, may be empty
   [:steps       [:sequential :string]]])           ;; plain text, one per step

(def RecipeContent
  [:map
   [:title             :string]
   [:sections          [:sequential {:min 1} RecipeSection]]
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]])
```

- A simple recipe is one unnamed section — no special case anywhere.
- `instructions-html` and top-level `ingredients` are **removed**, not
  deprecated. There is exactly one representation.
- Steps are plain text. If inline rich text is ever genuinely needed, it can be
  accreted later; scrapes never carried reliable rich text anyway.
- `servings`/times stay recipe-level (schema.org totals; per-section times are
  speculative).
- `CreateRecipeRequest`, `UpdateRecipeRequest`, `GetRecipeResponse`, and
  `ScrapeResult` all reference `RecipeContent` and inherit the change; the
  shared shape remains the single point of truth for both `content` and the
  immutable `original-content`.

### 2. Database

No schema change — content is an opaque JSONB value. The `:ingredient` filter
in `api/recipes.clj#get-recipes` traverses sections (one path, no legacy
branch):

```sql
EXISTS (SELECT 1
        FROM jsonb_array_elements(content->'sections') s,
             jsonb_array_elements_text(s.value->'ingredients') i
        WHERE i ILIKE '%…%')
```

Still Postgres-only; recipe tests stay on embedded-postgres. Update the stale
shape comment in `20260711000001-add-recipes.up.sql` (comment-only edit;
Migratus tracks ids, not checksums — the migration does not re-run).

### 3. Scraper (`api/recipe_scraper.clj`) — extraction and grouping are separate jobs

**Extraction (deterministic).** `parse-json-ld` produces verbatim facts:
`ingredient-lines: [string]` (from `recipeIngredient`) and steps with any
section names schema.org provides — `HowToStep.text` verbatim, `HowToSection`
names retained as candidate section boundaries. `instructions->html` is
deleted.

**Section signals.** The scrape is "sectioned" when either:
- `HowToSection`s appear in `recipeInstructions`, or
- ingredient lines look like headers (e.g. trailing `:` , leading "For the …")
  — the signal for pages that section only their ingredients, which JSON-LD
  cannot express.

**Unsectioned (the common case):** emit one unnamed section with all
ingredients and all steps. `extraction-method "json-ld"`. Zero LLM cost.

**Sectioned:** one constrained LLM call (existing Haiku fallback model) is
given the numbered verbatim ingredient lines, numbered verbatim steps, and any
candidate section names, and returns *only a grouping*:

```json
{"sections": [{"name": "Cake", "ingredients": [0,1,2], "steps": [0,1]},
              {"name": "Frosting", "ingredients": [3,4], "steps": [2,3]}]}
```

A deterministic merge builds `RecipeContent` from the indices. The grouping is
mechanically validated — every ingredient index and step index appears exactly
once (a partition). On any failure (bad JSON, missing/duplicated indices) fall
back to the single unnamed section and append a warning. Header-like
ingredient lines consumed as section names are dropped from the ingredient
lists by the grouping (the LLM is told headers are not ingredients).
`extraction-method "json-ld+llm-sections"`.

**No-JSON-LD fallback (existing LLM path):** the full-extraction prompt now
returns `sections: [{name, ingredients, steps}]` (strict JSON, no HTML) — a
single nil-named section when the page isn't sectioned. `extraction-method
"llm"` as today.

`ScrapeResult`'s `extraction-method` enum becomes
`["json-ld" "json-ld+llm-sections" "llm"]`.

### 4. HTTP layer

No route changes. `->slug` unchanged (`title` stays top-level). Malli request
validation picks up the new shape via `models.recipes`.

### 5. Testing (embedded-postgres, per existing recipes-test setup)

- Sectioned create/read round-trip: two named sections, order preserved.
- `:ingredient` filter matches a line inside the *second* section.
- Scraper, extraction: JSON-LD fixture with `HowToSection`s yields verbatim
  steps + candidate names; plain fixture yields one unnamed section with
  `"json-ld"` and no LLM call (assert via stubbed LLM).
- Scraper, signals: header-shaped ingredient lines ("For the frosting:")
  trigger the grouping call even with flat instructions.
- Scraper, grouping: stubbed grouping response merges into paired sections with
  byte-identical ingredient/step text; invalid grouping (missing index,
  duplicate index, malformed JSON) falls back to one section + warning.
- Full-LLM fallback output validates against the new `RecipeContent`.
- HTTP round-trip through `CreateRecipeRequest`/`GetRecipeResponse`.

### 6. `kaleidoscope-ui` impact (separate repo, tracked separately)

- Renderer: sections map to headed ingredient lists + `<ol>`s — simpler than
  parsing HTML.
- Editor: instructions change from one TipTap document to a per-step list
  (add/remove/reorder text rows) inside each section. This is the real cost of
  Decision 3; it is paid once, pre-release, and buys step-level features
  (cook mode, timers, per-step media) that an HTML blob forecloses.

### 7. Pre-merge cleanup (one-time, replaces all legacy-data machinery)

- Delete the flat-shape dev rows from staging/ephemeral recipes tables (or
  `DELETE FROM recipes` — they are test scrapes, not user data). Ephemeral
  envs are recreated from scratch anyway.
- Update stale shape comments: `20260711000001-add-recipes.up.sql`,
  `api/recipes.clj` ns docstring, base-feature PLAN.md notes.

## Out of scope

- Structured quantity parsing / per-section scaling (ingredients remain
  freeform strings; this shape enables it later).
- Rich text inside steps.
- Reusable cross-recipe sub-recipes.
- Per-section prep/cook times.
