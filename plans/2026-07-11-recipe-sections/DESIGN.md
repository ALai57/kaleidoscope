# Recipe Sections — Design

**Date:** 2026-07-11
**Status:** Approved
**Context:** plans/2026-07-10-recipes-feature/PLAN.md (base feature), plans/2026-07-11-recipe-scrape-fallback (LLM fallback)

## Problem

A baking recipe often has distinct components — e.g. a layer cake with **Cake**
and **Frosting** — each with its own ingredients *and* its own instructions.
Today `RecipeContent` is flat (`ingredients: [string]`, one `instructions-html`
blob), so:

- Ingredient sectioning is lost entirely (schema.org `recipeIngredient` is flat
  strings; there is no ingredient-section concept in JSON-LD at all).
- Instruction sectioning survives only as `<h3>`s baked into
  `instructions-html` by the scraper's `HowToSection` handling — invisible to
  the model.
- The UI cannot pair a component's ingredients with its instructions (e.g. show
  frosting ingredients next to frosting steps).

## Decisions (made with Andrew)

1. **Sections are paired components**: each section owns its ingredients AND
   its instructions. Not display-only grouping; not reusable cross-recipe
   sub-recipes (explicitly out of scope).
2. **Scrape pairing comes from the LLM only when needed**: the JSON-LD path
   stays LLM-free for unsectioned recipes; when JSON-LD contains
   `HowToSection`s, the page is routed through the existing Haiku extractor to
   produce properly paired sections.

## Approaches considered

- **A. Sections become the canonical `RecipeContent` shape** — CHOSEN.
  One shape, no special cases; a simple recipe is one section with `name nil`.
- **B. Optional `sections` overlay next to the flat fields** — rejected: dual
  representation of the same data; exactly the drift the "same shape, cannot
  drift" rule for `content`/`original_content` exists to prevent.
- **C. Relational `recipe_sections` table** — rejected: no per-section query
  need, breaks the single-JSONB-value design, turns every write into child-row
  diffing.

## Design

### 1. Model (`src/kaleidoscope/models/recipes.cljc`)

```clojure
(def RecipeSection
  [:map
   [:name              [:maybe :string]]        ;; nil ⇒ unnamed/only section
   [:ingredients       [:sequential :string]]   ;; freeform lines, unchanged
   [:instructions-html {:optional true} :string]])

(def RecipeContent
  [:map
   [:title             :string]
   [:sections          [:sequential {:min 1} RecipeSection]]
   [:servings          {:optional true} [:maybe :string]]
   [:prep-time-minutes {:optional true} [:maybe :int]]
   [:cook-time-minutes {:optional true} [:maybe :int]]])
```

- Top-level `ingredients` / `instructions-html` are **removed**, not
  deprecated. Sections are the only representation.
- `servings` and times stay recipe-level.
- Cake+frosting = two named sections. Renderers show a section header only when
  a section has a name.
- `CreateRecipeRequest`, `UpdateRecipeRequest`, `GetRecipeResponse`, and
  `ScrapeResult` all reference `RecipeContent`, so they inherit the change —
  the shared shape remains the single point of truth for both `content` and
  the immutable `original-content`.

### 2. Database

No schema change — recipe content is an opaque JSONB value. The `:ingredient`
filter in `api/recipes.clj#get-recipes` changes to traverse sections:

```sql
EXISTS (SELECT 1
        FROM jsonb_array_elements(content->'sections') s,
             jsonb_array_elements_text(s.value->'ingredients') i
        WHERE i ILIKE '%…%')
```

Still Postgres-only (`jsonb_array_elements`), same caveat as today: recipe
tests run on embedded-postgres, not H2.

### 3. Legacy data — read-time normalization, no data migration

The feature branch is unmerged; only a handful of staging/ephemeral rows exist.
A SQL data migration would need `jsonb_build_object`, which embedded H2 (which
parses every migration during test boot) cannot handle.

Instead, `->content` in `api/recipes.clj` — the existing normalization point —
additionally wraps a flat legacy value into
`[{:name nil :ingredients … :instructions-html …}]` at read time:

- Handles the immutable `original-content` forever without touching it.
- All writes store the sectioned shape, so `content` self-heals on first edit.
- Known trade-off: unedited legacy staging rows won't match the new
  sections-shaped SQL `:ingredient` filter until resaved. Acceptable at
  current data volume (a few of Andrew's test scrapes).

### 4. Scraper (`api/recipe_scraper.clj`)

- **JSON-LD path, no `HowToSection`** (the common case): emit one section with
  `name nil`, all ingredients, instructions rendered to HTML as today.
  `extraction-method "json-ld"`, zero LLM cost.
- **JSON-LD path, `HowToSection` present**: route the page text through the
  existing Haiku fallback extractor. `extraction-method "llm"`.
- **LLM prompt**: updated to always return
  `sections: [{name, ingredients, instructions_html}]` — a single nil-named
  section when the recipe isn't sectioned — so there is exactly one output
  format to validate against `RecipeContent`.

### 5. HTTP layer

No route changes. `->slug` unchanged (`title` stays top-level). Malli request
validation picks up the new shape via `models.recipes`.

### 6. Testing (embedded-postgres, per existing recipes-test setup)

- Sectioned create/read round-trip (two named sections preserved in order).
- `:ingredient` filter matches a line inside the *second* section.
- Read-normalization: raw-insert a legacy flat `content` row; `get-recipes`
  returns it wrapped in a single nil-named section (and same for
  `original-content`).
- Scraper fixtures: JSON-LD with `HowToSection` routes to the LLM and yields
  paired sections; plain JSON-LD stays `"json-ld"` with one section; LLM
  fallback output validates against the new `RecipeContent`.
- HTTP round-trip through `CreateRecipeRequest`/`GetRecipeResponse`.

## Out of scope

- `kaleidoscope-ui` (separate repo): section-aware editor + renderer needed to
  surface this; tracked separately.
- Structured quantity parsing / per-section scaling: ingredients remain
  freeform strings. "Scale just the frosting" is a future feature this shape
  enables but does not implement.
- Reusable cross-recipe sub-recipes.
