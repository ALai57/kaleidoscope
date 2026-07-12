# Recipes Feature — Scrape, Edit, Search, Share

> **Superseded in part:** `RecipeContent` was reshaped to paired sections with
> steps-as-data (no `instructions-html`) before first release — see
> `plans/2026-07-11-recipe-sections/DESIGN.md`. Shape details below are historical.

> **Implementation status (2026-07-11)** — built on branch `plans/recipes-feature` in both repos.
> **Backend (kaleidoscope), done + tested:** migration (5 tables, one-per-group + composite-tenant constraints), `api/access` shared visibility, `api/recipes` (CRUD, labels/groups, one-per-group validation, ingredient search, audiences), `api/recipe_scraper` (SSRF-guarded fetch, JSON-LD, LLM fallback), `http_api/recipes` routes + ACL. Full suite green (196 tests). Phases 1, 3, 4, 5 complete.
> **Frontend (kaleidoscope-ui), done + tested:** `types/recipe`, `api/recipes` (+13 tests), `LabelPicker` (+7 tests), `RecipesPage`/`RecipePage`/`RecipeEditorPage` (+ list-filter and scrape→save tests), routes/nav/manager card. Typecheck + lint clean; 24 recipe tests green. Phase 2 complete.
> **Deferred:** `e2e/recipe-editor.spec.ts` (Playwright) not written; scraper LLM fallback exercised only via mock (no live Anthropic call); a real structured content-diff in "View original" is a plain list, not a highlighted diff. **Note:** the UI repo needs Node 22 (`.nvmrc`); the default shell Node (18) can't run vitest — `nvm use 22` first.
>
> **Revision (2026-07-11)** — data model reworked after a soundness/simplicity review:
> (1) single opaque `UUID` identity, slug is the address; (2) one `RecipeContent` value/spec for both current and original — no differently-shaped snapshot blob; (3) ingredients named honestly as freeform text + text-contains match; (4) one-per-group invariant moved into the DB (partial unique index) with the group carried on the assignment row; (5) same-tenant integrity enforced by composite `(id, hostname)` FKs; (6) shared access/visibility helper instead of copying the article logic. Recipe tests run on embedded-postgres.

## Context

The user collects recipes from the web, modifies them, and hates the exposition/blog-text on recipe sites. This feature adds a **Recipes** domain across both repos: scrape a recipe from a URL into clean structured data, save and edit it, search by ingredient, categorize with a curated label system, and share with groups exactly like articles.

**Approved decisions:**
- **Scraping**: server-side fetch → parse schema.org Recipe JSON-LD when present → fall back to LLM extraction via the backend's existing Anthropic client (`post-anthropic-sync` + `extract-json` in `src/kaleidoscope/workflows/llm_executor.clj`).
- **Identity vs. address**: a recipe's identity is a single opaque `UUID` (matches the modern tables — projects/workflows/tags — *not* the legacy `articles` bigint sequence, which CLAUDE.md says not to propagate). `recipe_url` (slug) is the *address* you look it up by, never a second identity. All recipe-domain PKs/FKs are native `UUID`. The **one** exception is references to the legacy `groups(id)`, which is `VARCHAR(36)` — recipe-audiences match that column type at that boundary and nowhere else.
- **Data model — one recipe-content value**: title, ingredients, instructions, servings, and times form a single **`RecipeContent`** value, defined by *one* Malli spec and stored in *one* JSONB shape. The current recipe and the immutable original scrape are both that same spec (`content` and `original_content`) — they cannot drift, because "same shape" is enforced, not hoped. Identity/place/tenant fields (id, recipe_url, hostname, visibility, timestamps) stay as columns; they are not content.
- **Ingredients are freeform text lines, matched by text-contains** — an honest name for what the model is. Each is a string like `"2 cups flour"` (quantity+unit+item, unparsed); "search" is substring `ILIKE`, i.e. *text contains*, not structured ingredient search. No `{qty, unit, item}` normalization — that's speculative generality for a personal collection; a `pg_trgm` index is a drop-in later if ranking/recall ever matter.
- **Labels (Linear-style, not freeform tags)**: a user-defined, reusable collection of labels. Labels may optionally belong to a **label group** (e.g. group `ethnicity` with labels `indian`, `mexican`); **at most one label from a group can apply to a recipe**. This invariant lives **with the data**: the assignment row carries `group_id`, and a partial unique index enforces it in the DB (not scattered app validation). Qualified display/selection form is `group/label` (`ethnicity/indian`); ungrouped labels are just `baking`, `carbs`. The goal is reuse of existing labels over ad-hoc creation.
- **Tenant integrity is enforced by the schema, not remembered by code**: `hostname` is on every recipe-domain table, and composite foreign keys `(child_id, hostname) → parent(id, hostname)` make cross-tenant assignment (a recipe on host A wearing a label from host B) structurally impossible, rather than an app-code promise.
- **Versioning**: no branch/version machinery — but the original is *not* a special-cased differently-shaped blob. It is one more `RecipeContent` value. If a full history is ever wanted, it becomes an append-only table of that same value; nothing is rewritten.
- **Shared concept, named on purpose**: recipes and articles are both hostname-scoped, slug-addressed, group-shareable content. The audience/visibility logic is *extracted and shared*, not copied into a parallel universe (see 1.2) — so a future change to "who can see this" is one edit, not two that drift.
- **Scope**: both repos — backend `../kaleidoscope` (Clojure) and frontend (this repo).

---

## Part 1 — Backend (`/Users/alai/code/kaleidoscope`)

### 1.1 Migration: `resources/migrations/<timestamp>-add-recipes.up.sql` / `.down.sql`

```sql
CREATE TABLE recipes (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),  -- identity (opaque; matches modern tables, not the legacy articles bigint seq)
  recipe_url        VARCHAR NOT NULL,          -- address (slug), NOT a second identity
  hostname          VARCHAR NOT NULL,
  content           JSONB NOT NULL,            -- the RecipeContent value: {title, ingredients[], instructions_html, servings?, prep_time_minutes?, cook_time_minutes?}
  original_content  JSONB,                     -- immutable scrape; SAME RecipeContent spec — cannot drift in shape
  source_url        TEXT,
  author            VARCHAR,
  public_visibility BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  modified_at       TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (recipe_url, hostname),
  UNIQUE (id, hostname)                        -- target for child tables' composite (id, hostname) FKs
);
--;;
CREATE TABLE recipe_label_groups (        -- e.g. "ethnicity"
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       VARCHAR NOT NULL,
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (name, hostname),
  UNIQUE (id, hostname)
);
--;;
CREATE TABLE recipe_labels (              -- e.g. "indian" (group=ethnicity) or "baking" (no group)
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name       VARCHAR NOT NULL,
  group_id   UUID,                        -- NULL = ungrouped
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (name, group_id, hostname),
  UNIQUE (id, hostname),
  FOREIGN KEY (group_id, hostname) REFERENCES recipe_label_groups (id, hostname) ON DELETE CASCADE  -- label & group share a tenant
);
--;;
CREATE TABLE recipe_label_assignments (   -- recipe ↔ label join
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  recipe_id  UUID NOT NULL,
  label_id   UUID NOT NULL,
  group_id   UUID,                        -- denormalized from the label so the one-per-group invariant lives on THIS row
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (recipe_id, label_id),
  FOREIGN KEY (recipe_id, hostname) REFERENCES recipes (id, hostname)       ON DELETE CASCADE,  -- recipe & assignment share a tenant
  FOREIGN KEY (label_id, hostname)  REFERENCES recipe_labels (id, hostname) ON DELETE CASCADE   -- label & assignment share a tenant
);
--;;
-- One label per group per recipe — enforced IN THE DB, not in scattered app validation.
-- Partial unique index requires Postgres; recipe tests therefore run on embedded-postgres (they need it for JSONB search anyway).
CREATE UNIQUE INDEX recipe_one_label_per_group
  ON recipe_label_assignments (recipe_id, group_id)
  WHERE group_id IS NOT NULL;
--;;
CREATE TABLE recipe_audiences (           -- who a recipe is shared with
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  group_id   VARCHAR(36) NOT NULL REFERENCES groups(id) ON DELETE CASCADE,  -- VARCHAR(36) to match legacy groups(id) — the sole boundary exception
  recipe_id  UUID NOT NULL,
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE (group_id, recipe_id),
  FOREIGN KEY (recipe_id, hostname) REFERENCES recipes (id, hostname) ON DELETE CASCADE
);
```

Design notes:
- **One `RecipeContent` value, one Malli spec.** `content` and `original_content` are the same JSONB shape (`{title, ingredients [str], instructions_html, servings?, prep_time_minutes?, cook_time_minutes?}`), validated by a single `RecipeContent` spec in `api/recipes.clj`. Current and original literally cannot drift in shape. `original_content` is written once at create and never touched again; "what did I change?" is a value-vs-value comparison of two `RecipeContent`s, not eyeballing a foreign-shaped blob. The existing `rdbms.clj` JSONB↔Clojure round-tripping (`->pgobject`/`<-pgobject`) handles both columns with zero new code.
- **Ingredients are freeform text lines, not structured data — named honestly.** Each is a string (`"2 cups flour"`); ingredient "search" is text-contains: `EXISTS (SELECT 1 FROM jsonb_array_elements_text(content->'ingredients') i WHERE i ILIKE '%…%')`. This will match substrings (`flour` inside `cauliflower`) — acceptable for a personal collection; do not advertise it as structured search. `pg_trgm` GIN index is a drop-in later if recall/ranking ever matter. No `{qty, unit, item}` normalization — speculative generality here.
- **Identity is one opaque `UUID`.** No bigint sequence (leaks ordering/count and copies a legacy pattern CLAUDE.md fences off). The slug (`recipe_url`) addresses; the UUID identifies; do not conflate them.
- **Invariants live with the data, enforced by the schema:**
  - *One label per group per recipe* → assignment carries `group_id` and a partial unique index (`recipe_one_label_per_group`) enforces it. App code still validates on write to return a clean **400** before hitting the constraint, but the DB is the source of truth, not the only-hope. Ungrouped labels (`group_id IS NULL`) are excluded from the index and bounded only by `UNIQUE(recipe_id, label_id)` — which is exactly right (no "one per null-group" pseudo-rule).
  - *Same-tenant integrity* → composite `(child_id, hostname)` FKs make a cross-tenant label/audience assignment structurally impossible. Not an app-code promise.
- **Tests run on embedded-postgres, not H2**, for the recipe domain: the partial unique index and `jsonb_array_elements_text` are both Postgres features, and the test DB must model the same invariants production relies on. `embedded-postgres/fresh-db!` already exists for exactly this (workflow tables use it).
- **Labels are normalized, tenant-scoped rows** — the same idea as the existing-but-unused `tags` table (`20230603195843`), extended with groups and native `UUID`.
- **No separate manager/published split** (no `/compositions` analog needed): the ACL supports `:request-method`, so one `/recipes` namespace works.

### 1.2 Business logic: `src/kaleidoscope/api/recipes.clj`

Structure like `api/articles.clj`, but **share the access concept, don't clone it** (see below):
- **`RecipeContent` Malli spec** — the single definition of a recipe's content shape. Validate on every write; serialize into both `content` (always) and `original_content` (create only). One spec, two columns, guaranteed same shape.
- `get-recipes` — honeysql query scoped by `hostname`, returning each recipe with its labels attached (join through `recipe_label_assignments` → `recipe_labels` ⟕ `recipe_label_groups`; aggregate or a second query + group-by). Optional filters:
  - `:ingredient` → `EXISTS (SELECT 1 FROM jsonb_array_elements_text(content->'ingredients') i WHERE i ILIKE '%…%')` (text-contains, not structured search)
  - `:label-id` → `EXISTS` over the assignments join
- **Shared access helper** — the "who can see this" logic (caller's group memberships via `groups/get-users-groups` → visible = `public_visibility` ∪ shared via an audiences table ∪ writer-sees-all) is *the same concept* as `get-published-articles`. Extract it once — e.g. `kaleidoscope.api.access/visible-filter` parameterized by `{:audience-table :owner-column …}` — and call it from both articles and recipes, rather than copy-pasting a second divergent copy. If touching `articles.clj` to route it through the shared fn is out of scope, still put the recipe copy *in* the shared ns so the next domain reuses it (a genuinely new abstraction, not speculative — two concrete callers).
- `create-recipe!` / `update-recipe!` — accept `label-ids`; validate content against `RecipeContent`; validate one-per-group (return 400 before the DB constraint fires); replace the assignment set atomically (delete + insert in a transaction, stamping each assignment's `group_id`/`hostname` from its label). Bump `modified_at`; never touch `original_content` after create. `delete-recipe!`.
- Label CRUD: `get-labels` (with group name joined, so callers can render `ethnicity/indian`), `create-label!` (name + optional group-id), `rename-label!`, `delete-label!`; `get-label-groups`, `create-label-group!`, `rename-label-group!`, `delete-label-group!` (cascades to its labels and their assignments). All tenant-scoped; assignments inherit `group_id` from the label so the invariant index has what it needs.
- Audiences: `add-audience-to-recipe!`, `get-recipe-audiences`, `delete-recipe-audience!` — thin wrappers over the shared audience helper, stamping `hostname` for the composite FK.

### 1.3 Scraper: `src/kaleidoscope/api/recipe_scraper.clj`

1. **Fetch**: JDK `java.net.http.HttpClient` (already the codebase's HTTP client — see `llm_executor.clj`): follow redirects, ~10s timeout, browser-ish `User-Agent`, cap body size (~2 MB), require `http(s)` scheme.
   **SSRF guard**: resolve the host and reject loopback/private/link-local ranges (`InetAddress` checks: `isLoopbackAddress`, `isSiteLocalAddress`, `isLinkLocalAddress`, plus 169.254.169.254) before fetching.
2. **JSON-LD pass** (no new deps): regex out `<script type="application/ld+json">…</script>` blocks, parse each with cheshire (already a dep). Find the object with `@type` = `Recipe` (handle `@graph` wrappers, `@type` as array, and top-level arrays). Map:
   - `name` → title; `recipeIngredient` → ingredients (strings, as-is)
   - `recipeInstructions` → HTML `<ol><li>…` (handle: plain string, `HowToStep[]` via `.text`, `HowToSection[]` → `<h3>` + nested steps)
   - `prepTime`/`cookTime` ISO-8601 durations → minutes via `java.time.Duration/parse` (guard malformed values)
   - `recipeYield` → servings (string or first of array); `keywords`/`recipeCategory`/`recipeCuisine` → `suggested-labels` (plain strings — **suggestions only**; the frontend offers to map them onto the user's existing labels, never auto-creates labels)
3. **LLM fallback** (when no usable JSON-LD): strip `<script>`/`<style>`/tags to text, truncate (~50k chars), prompt `post-anthropic-sync` for strict JSON `{title, ingredients: [...], instructions_html, servings, prep_time_minutes, cook_time_minutes, suggested_labels: [...]}`, parse with `extract-json`. Use a cheap model (haiku-class), not the executor's opus default.
4. Return a **draft** `{recipe <RecipeContent> suggested-labels [...] extraction-method "json-ld"|"llm" warnings [...]}` — `recipe` is a `RecipeContent` value (same spec the DB stores), validated before return so a bad scrape fails here, not at save. The scrape endpoint **does not save**; the user reviews/edits in the frontend, then POSTs it as both `content` and `original_content`. Errors: 422 with a reason (`fetch-failed`, `no-recipe-found`, `blocked-url`).

### 1.4 Routes: `src/kaleidoscope/http_api/recipes.clj` + wiring in `http_api/kaleidoscope.clj`

`reitit-recipes-routes` (kebab-case wire, Malli-coerced):

| Method + path | Purpose |
|---|---|
| `GET /recipes?ingredient=&label-id=` | Visible recipes for caller (access-filtered), labels included, optional filters |
| `GET /recipes/:recipe-url` | One recipe with labels (access-checked) |
| `POST /recipes` | Create (slug from title, mirror frontend `titleToSlug`; accepts `label-ids`; sets `content`, and `original_content` if provided) |
| `PUT /recipes/:recipe-url` | Update editable fields incl. `label-ids` (validated one-per-group) and `public-visibility` |
| `DELETE /recipes/:recipe-url` | Delete |
| `POST /recipes/scrape` body `{url}` | Fetch+extract → draft + suggested labels (not saved) |
| `GET /recipe-labels` | All labels for tenant, each with `group-id`/`group-name` |
| `POST /recipe-labels` body `{name, group-id?}` | Create label |
| `PUT /recipe-labels/:id` / `DELETE /recipe-labels/:id` | Rename / delete label |
| `GET /recipe-label-groups` / `POST` / `PUT /:id` / `DELETE /:id` | Group CRUD (delete cascades) |
| `PUT /recipe-audiences` body `{recipe-id, group-id}` | Share with group |
| `GET /recipe-audiences?recipe-id=` | List shares |
| `DELETE /recipe-audiences/:id` | Unshare |

Mount route vectors in `kaleidoscope-app`; add to `KALEIDOSCOPE-ACCESS-CONTROL-LIST` (before the fail-closed catch-all), using the method-based style already used for `/themes`:

```clojure
{:pattern #"^/recipes/scrape" :handler auth/require-*-writer}
{:pattern #"^/recipes.*" :request-method :get    :handler auth/public-access}  ; filtered internally
{:pattern #"^/recipes.*" :request-method :post   :handler auth/require-*-writer}
{:pattern #"^/recipes.*" :request-method :put    :handler auth/require-*-writer}
{:pattern #"^/recipes.*" :request-method :delete :handler auth/require-*-writer}
{:pattern #"^/recipe-labels.*"       :request-method :get :handler auth/public-access}
{:pattern #"^/recipe-labels.*"       :handler auth/require-*-writer}
{:pattern #"^/recipe-label-groups.*" :request-method :get :handler auth/public-access}
{:pattern #"^/recipe-label-groups.*" :handler auth/require-*-writer}
{:pattern #"^/recipe-audiences.*"    :handler auth/require-*-writer}
```

(Label GETs are public so the reader page can render label chips on shared/public recipes. Note: article-audiences is admin-gated; recipes use writer to keep sharing usable — flag if admin is preferred.)

---

## Part 2 — Frontend (this repo)

### 2.1 Types: `src/types/recipe.ts` (re-export from `src/types/index.ts`)

```ts
export interface RecipeLabelGroup { id: string; name: string; }   // UUID
export interface RecipeLabel {
  id: string;                    // UUID
  name: string;                  // "indian"
  group_id?: string | null;
  group_name?: string | null;    // "ethnicity" — render as `${group_name}/${name}` when grouped
}
// RecipeContent is the ONE shared shape: current recipe and the scraped original are both this.
export interface RecipeContent {
  title: string;
  ingredients: string[];         // one freeform line per ingredient ("2 cups flour")
  instructions_html: string;     // HTML (TipTap)
  servings?: string;
  prep_time_minutes?: number;
  cook_time_minutes?: number;
}
export interface Recipe extends RecipeContent {
  id: string;                    // UUID — identity
  recipe_url: string;            // slug — address, not identity
  labels: RecipeLabel[];
  source_url?: string;
  original_content?: RecipeContent; // immutable scrape — SAME shape as the editable content
  public_visibility: boolean;
  author?: string;
  created_at: string;
  modified_at: string;
}
export interface ScrapeResult { recipe: RecipeContent; suggested_labels: string[]; extraction_method: 'json-ld'|'llm'; warnings: string[]; }
export interface RecipeAudience { id: string; recipe_id: string; group_id: string; }
```

### 2.2 API client: `src/api/recipes.ts`

Same pattern as `src/api/articles.ts` (shared `request` from `src/api/client.ts`, optional `token`, auto snake↔kebab):
`getRecipes({ingredient?, labelId?}, token?)`, `getRecipe(slug, token?)`, `createRecipe(RecipeContent & {label_ids, original_content?}, token)` (slug via existing `titleToSlug` in `src/utils/url.ts`), `updateRecipe(slug, patch, token)`, `deleteRecipe(slug, token)`, `scrapeRecipe(url, token)`; label CRUD (`getLabels`, `createLabel`, `renameLabel`, `deleteLabel`, `getLabelGroups`, `createLabelGroup`, …); plus `addRecipeAudience`/`getAudiencesForRecipe`/`deleteRecipeAudience` mirroring the `/article-audiences` trio in `src/api/groups.ts`.

### 2.3 Label picker component: `src/components/recipes/LabelPicker.tsx`

The one genuinely new UI piece — Linear-style semantics, reused by editor and list filter:
- MUI `Autocomplete` (multiple) over `['recipe-labels']`, options rendered and searched by qualified name (`ethnicity/indian` for grouped, `baking` for ungrouped), grouped in the dropdown via Autocomplete's `groupBy` (group name, "Other" for ungrouped).
- **One-per-group rule**: selecting a grouped label **replaces** any currently-selected label from the same group (implement in `onChange`: drop prior same-`group_id` selections). Mirrors Linear exactly; no error states needed.
- **Curated, not freeform**: no silent create-on-type. A "+ New label…" affordance (when the query matches nothing) opens a small create dialog (name + optional group select / new group) hitting `createLabel`/`createLabelGroup`, then selects it — creation is deliberate, reuse is the default path.
- Selected labels render as chips titled with the qualified name.

### 2.4 Pages (flat in `src/pages/`, inline TanStack Query hooks per convention)

- **`RecipesPage.tsx`** (`/recipes`) — list + search. Query key `['recipes', {ingredient, labelId}]` hitting the server filters (debounce ingredient input with existing `useDebouncedCallback`). Ingredient search field + label filter chips (all labels from `['recipe-labels']`, qualified names), modeled on `ArchiveView` in `ArticlePage.tsx`. Writer/admin sees "New recipe" + edit/delete actions (same role-check style as `NavBar.tsx`'s `isSiteAdmin`). Also hosts a **"Manage labels"** dialog (writer-only): list groups + labels with rename/delete, modeled on `GroupsPage.tsx`'s accordion CRUD pattern.
- **`RecipePage.tsx`** (`/recipes/:slug`) — reader: title, meta row (servings, prep/cook time, source link, qualified label chips), ingredients as a checklist (`Checkbox` list — nice for cooking), instructions rendered via `RichTextEditor` with `editable={false}` (existing read-only pattern).
- **`RecipeEditorPage.tsx`** (`/recipes/new`, `/recipes/:slug/edit`):
  - **Scrape bar** (new-recipe mode): URL field + "Import" → `useMutation(scrapeRecipe)` → on success populate the form from the returned `RecipeContent` and stash the untouched copy as `original_content`; show `extraction_method`/warnings. `suggested_labels` render as ghost chips — clicking one selects a matching existing label (case-insensitive name match) or opens the create dialog prefilled; never auto-created. Nothing saved until the user clicks Save.
  - **Form**: title, servings, prep/cook minutes; **ingredients editor** = list of `TextField` rows with add/remove/reorder (plain `useState<string[]>`); **labels** = `LabelPicker`; **instructions** = existing `RichTextEditor` + `extensions.ts` + `EditorToolbar`, HTML kept in a `useRef` like `ArticleEditorPage.tsx`.
  - **"View original"** collapsible panel when `original_content` exists — since it's the same `RecipeContent` shape as the current form, a field-by-field diff is trivial (highlight changed lines), not just eyeballing.
  - **Sharing**: copy the `VisibilityModal` from `ArticleManagerPage.tsx` (radio: public vs audience; chips add/remove) pointed at the recipe-audience API; query key `['recipe-audiences', recipeId]`.
- No separate DataGrid manager page — manage from the list/editor. Add a Recipes `Capability` card to `CAPABILITIES` in `src/pages/ManagerPage.tsx` and a "Recipes" entry to `NAV_LINKS` in `src/components/layout/NavBar.tsx`.

### 2.5 Routes (`src/App.tsx`, React.lazy like the article block)

`/recipes` → RecipesPage; `/recipes/new` → RecipeEditorPage; `/recipes/:slug` → RecipePage; `/recipes/:slug/edit` → RecipeEditorPage. (Register `/recipes/new` before `/recipes/:slug`.)

---

## Part 3 — Testing

**Backend** — recipe tests use `embedded-postgres/fresh-db!` (the partial unique index and JSONB functions are Postgres-only; the test DB must model the same invariants as prod):
- `test/kaleidoscope/api/recipes_test.clj` — CRUD, `RecipeContent` spec validation (bad content → 400), `original_content` set once and immutable across updates, hostname scoping, visibility filtering (public / group-shared / hidden) **via the shared access helper**, ingredient (text-contains) + label filters, label CRUD, group-delete cascade.
- **Invariant tests, at both layers**: one-label-per-group rejected in app code with a clean 400 (`ethnicity/indian` + `ethnicity/mexican`), *and* a direct-DB insert bypassing app code is rejected by the `recipe_one_label_per_group` index; replacing within a group works; two ungrouped labels coexist. Same-tenant FK: inserting an assignment/audience whose recipe and label/group differ in `hostname` fails.
- Model on `api/articles_test.clj` + `api/groups_test.clj`.
- Scraper unit tests with canned HTML fixtures: JSON-LD happy path (incl. `@graph`, `HowToSection`, ISO durations), malformed JSON-LD → fallback signal, SSRF rejection. Stub the HTTP fetch + LLM call (mock executor pattern already exists: `"mock"` launcher in `init/env.clj`).
- Route-level auth checks in the style of `http_api/kaleidoscope_test.clj` (401 on anonymous POST `/recipes`, 200 anonymous GET `/recipes` and `/recipe-labels`).

**Frontend**:
- `src/api/recipes.test.ts` — MSW per-endpoint, Bearer-header assertion, ApiError case (template: `src/api/articles.test.ts`).
- `src/components/recipes/LabelPicker.test.tsx` — same-group replacement behavior, qualified-name rendering, create dialog path.
- `src/pages/RecipeEditorPage.test.tsx` — mock `useAuth` + `RichTextEditor` (see `ArticleEditorPage.test.tsx`), MSW for scrape→populate→save flow incl. suggested-label mapping.
- `src/pages/RecipesPage.test.tsx` — list renders, ingredient search + label filter drive query params.
- `e2e/recipe-editor.spec.ts` — Playwright, API mocked via `page.route` (template: `e2e/article-editor.spec.ts`): import URL → draft appears → edit → save.

---

## Part 4 — Phasing (each lands independently)

1. **Backend CRUD + labels**: migration (all five tables + the one-per-group partial index + composite tenant FKs), `RecipeContent` Malli spec, shared `api/access` visibility helper, `api/recipes.clj` (recipe + label CRUD, one-per-group validation; no scrape), `http_api/recipes.clj`, ACL entries, api tests on embedded-postgres. Manual recipe entry now possible via API.
2. **Frontend core**: types, `api/recipes.ts`, `LabelPicker`, three pages (manual entry, no scrape bar yet), label management dialog, routes/nav, unit tests.
3. **Scraping**: `recipe_scraper.clj` (JSON-LD → LLM fallback, SSRF guard), `POST /recipes/scrape`, scrape bar + suggested-labels + original-snapshot UX in the editor, scraper tests + e2e.
4. **Search**: `ingredient`/`label-id` query params on `GET /recipes`, search/filter UI on `RecipesPage`.
5. **Sharing**: `recipe_audiences` endpoints + `VisibilityModal` port + audience tests. (Table already created in phase 1's migration.)

## Verification

- Backend: run its test suite (per `../kaleidoscope` tooling — `task`/`clj` targets) after each phase.
- Frontend: `npm run ci` (typecheck + lint + vitest), then `npm run test:e2e`.
- End-to-end manual check: backend `task run` + `npm run dev`, then as a writer: create label group `ethnicity` with labels `indian`/`mexican` and ungrouped `baking`; import a real recipe URL (e.g. a Serious Eats page — has JSON-LD) and confirm a clean draft with no exposition; edit an ingredient; apply `ethnicity/indian` then `ethnicity/mexican` and confirm it replaces rather than stacks; save; find it via ingredient search and by label filter; share it with a group; confirm an anonymous/other-user request only sees public/shared recipes.

## Risks / notes

- **Embedded DB choice is settled, not open**: recipe tests run on `embedded-postgres` because the model *depends* on Postgres features (partial unique index for the one-per-group invariant; `jsonb_array_elements_text` for search). Running them on H2 would be testing a weaker model than production enforces — the whole reason the invariant moved into the schema.
- **`gen_random_uuid()` availability**: confirm the extension/function is present in the embedded-postgres image (the modern UUID tables already rely on it — follow their migration setup); if a migration must `CREATE EXTENSION pgcrypto`, add it once.
- **LLM fallback cost/latency**: per-scrape token cost; mitigated by JSON-LD-first (covers the large majority of recipe sites) and a haiku-class model.
- **Sites blocking scrapers** (403/Cloudflare): return a clear 422 so the user can paste content manually into the editor instead.
- **Audience role level**: plan uses `require-*-writer` for `/recipe-audiences` (articles use admin) — confirm during phase 5.
- **Label deletion semantics**: deleting a label/group cascades assignments off recipes silently (Linear behaves the same); rename is the safe path and is supported.
