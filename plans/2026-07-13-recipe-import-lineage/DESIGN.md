# Design: Recipe import lineage view

**Date:** 2026-07-13
**Status:** Design — pending review
**Visual spec:** [Import Lineage — Prism artifact](https://claude.ai/code/artifact/e8b5d28e-e67e-4bff-82cd-e8733dd23097) (approved)

## Goal

The scrape pipeline already records a complete provenance log for every import —
`recipe → processing_run → raw_scrape` — but nothing surfaces it. Today the only
way to see *what happened* during an import is psql. This design adds the thin
read layer that exposes one recipe's import lineage, so a writer can open a
recipe and answer "which stage produced this, what technique ran, what did the
model see, and where did it go wrong?" without a DB shell.

The audience is the site owner acting as **model/prompt trainer**: the view is a
debugging surface for tweaking the extraction prompts and spotting stages that
underperform. It is not a reader-facing feature.

Two pieces:

1. **Backend (this repo):** a writer-only read endpoint that assembles a
   recipe's processing run and its raw scrape into one `RecipeLineage` view.
2. **Frontend (kaleidoscope-ui, separate repo):** the *Import Lineage* component
   that renders that view. Specified here as a contract + the approved visual
   mockup; built in the UI repo.

This is the read counterpart to the `raw-scrape-pipeline` DESIGN's deferred
"archive read/list API" — scoped down to the single-recipe case the page needs,
not the general run-comparison tooling (still out of scope).

## What the view shows

The lineage reads as the literal Prism metaphor: raw input enters, the
three-stage pipeline refracts it into ACQUIRE → PARSE → NORMALIZE, and the user
drills into any stage. Per the approved mockup, the surface carries, in order:

- **Run header** — outcome, source kind, request→final URL + HTTP status,
  `pipeline_version`, run timestamp, run id, and the three technique tags.
- **Refraction overview** — the three stages as a summary, each with its
  technique.
- **Run stats** — stages run, model-call count, token usage (in/out), header
  lines dropped.
- **A spine of three stage cards**, connected by artifact-handoff labels
  (`RawScrape → ExtractedFacts → RecipeContent`), each expandable to:
  - the **artifact** that stage produced (raw excerpt / extracted facts / final
    sections), and
  - any **LLM calls** it made — model, full system prompt, the exact request
    messages, and the raw response, each copyable. This is the prompt-tuning
    surface.
- **Warnings** for the run, surfaced prominently.
- **Failure path**: a short-circuited run renders the failing stage with its
  `error-detail` and later stages as *not reached*.

All of this is a **view over data the pipeline already stores** (see the
`raw-scrape-pipeline` DESIGN's data model) — no new capture, no schema change.
The two debug affordances are weighted equally: the artifact-to-artifact diff
(what each stage changed) and the per-call prompt/response (why the model did
it).

## Backend

### Endpoint

```
GET /recipes/:recipe-url/lineage
GET /recipes/:recipe-url/lineage?include-raw=true
```

- **Writer-only.** The response exposes raw HTML/transcripts and full LLM
  prompts — training material, not reader content. Non-writers (readers,
  anonymous) receive **404**, consistent with the codebase's practice of not
  leaking the existence of resources a caller may not see. (`writer?` already
  distinguishes writer/admin from reader/anonymous for the tenant.)
- **404** when the recipe does not exist for the host, or exists but has no
  `scrape_processing_run_id` (a manually-created recipe has no lineage).
- **`include-raw`** (default `false`) gates the potentially large
  `raw_content` body. The default response carries raw *metadata* (URL, status,
  tier, byte size) but not the HTML/transcript itself; the UI fetches the body
  only when a user expands the ACQUIRE inspector. Keeps the common response
  lean (the mockup shows an excerpt by default, full on demand).

### Response — `RecipeLineage`

Assembled from the three already-stored records; nothing is recomputed.

```clojure
{:recipe-url  "cardamom-buns"
 :recipe-id   #uuid "…"
 :run   {:id               #uuid "…"
         :pipeline-version "6133819"
         :outcome          "success"          ;; or a failure reason
         :error-detail     nil                ;; {message, reason} on failure
         :techniques       {:acquire :direct :parse :json-ld :normalize :llm-grouping}
         :facts            {…ExtractedFacts…}  ;; nil on early failure
         :content          {…RecipeContent…}   ;; nil on failure
         :llm-calls        [{:purpose :normalize :model "claude-haiku-4-5"
                             :request {…} :response {…}}]
         :warnings         ["…"]
         :created-at       #inst "…"}
 :raw   {:source-kind "url"
         :request-url "https://…"
         :final-url   "https://…"
         :http-status 200
         :fetch-tier  "direct"
         :content-bytes 49408                 ;; size only, unless include-raw
         :raw-content nil                     ;; populated only when include-raw=true
         :created-at  #inst "…"}}
```

`content-bytes` is derived (`count` of the stored `raw_content`) so the UI can
show "48 KB" without shipping the body. Token usage the stats row needs is read
from each `llm-calls[].response.usage`; there is no separate token column, and
we do **not** fabricate per-stage timing — `processing_runs` records only
`created_at`, not durations (noted so the UI doesn't imply timing we don't have).

### Layers

Strict 3-layer separation is preserved; this is almost entirely assembly over
existing readers.

- **`persistence/scrape_pipeline.clj`** — no change. `get-processing-run` and
  `get-raw-scrape` (by id + hostname) already exist and are reused as-is.
- **`api/recipes.clj`** — new `get-recipe-lineage`:
  1. resolve the recipe for `{hostname, recipe-url}` (reusing `get-recipe`);
  2. if it has no `scrape-processing-run-id`, return `nil`;
  3. load the run (`pipeline-db/get-processing-run`) and, from its
     `raw-scrape-id`, the raw scrape (`pipeline-db/get-raw-scrape`), both scoped
     to hostname;
  4. assemble the `RecipeLineage` map. When `include-raw?` is false, drop
     `:raw-content` and attach `:content-bytes`.
  No HTTP, no new SQL — domain assembly only. Access is decided in the handler
  (writer gate), matching how the other recipe routes split concerns.
- **`http_api/recipes.clj`** — new sub-route `/:recipe-url/lineage`. The handler
  gates on `authz/writer?`; non-writers → 404; missing recipe or missing run →
  404; else 200 with the assembled view. Threads `include-raw` from the query.

### Model / schema changes (`models/recipes.cljc`)

- New `RecipeLineage` Malli schema (the response above), reusing the existing
  `ExtractedFacts` and `RecipeContent` schemas for `:run.facts` / `:run.content`
  so the lineage view cannot drift from the pipeline's own artifact shapes.
- **No DB migration.** All fields already exist on `processing_runs` /
  `raw_scrapes` / `recipes`.

## Frontend contract (`kaleidoscope-ui`, separate repo)

Built in the UI repo against the approved mockup. Documented here so the
contract isn't missed:

- On a recipe page, when `GetRecipeResponse.scrape-processing-run-id` is present
  **and** the viewer is a writer, render a collapsed **Import lineage** strip
  (`outcome · acquire → parse → normalize · N model calls`). Absent run id, or
  non-writer → no strip.
- Expanding the strip fetches `GET /recipes/:recipe-url/lineage` and renders the
  trace (run header, refraction overview, stats, stage spine, warnings).
- Expanding the ACQUIRE raw inspector fetches
  `…/lineage?include-raw=true` (or a follow-up call) to pull the raw body.
- Styling follows the **Prism** design system (dark-committed instrument panel).
  Stage hues: ACQUIRE `#26A0BC`, PARSE `#9085E9`, NORMALIZE `#2E9E5B`; outcome
  uses Prism status tokens, kept separate from stage hues; interactive elements
  use the cyan accent.
- Copy buttons on each prompt/response block; `<details>`-based expand/collapse
  for keyboard access and reduced-motion friendliness.

## Testing

Every feature needs automated coverage; recipe tests run on embedded-postgres.

- **api** (`recipes_test`): `get-recipe-lineage` over a recipe created with a
  real `scrape-processing-run-id` assembles run + raw and reuses the stored
  artifact shapes; a recipe with no run returns `nil`; `include-raw?` toggles
  `:raw-content` vs `:content-bytes`; scoping — a run/raw from another hostname
  is never assembled.
- **http** (`recipes_test`): a writer gets 200 with the lineage for a scraped
  recipe, and the returned techniques/llm-calls/outcome round-trip what the
  pipeline stored; a non-writer gets 404; a recipe with no run gets 404; a
  missing recipe gets 404; `?include-raw=true` returns the raw body while the
  default omits it. Reuse the existing scrape→create lineage fixture from the
  photo-import end-to-end test so the run under test is a genuine pipeline
  product, not a hand-built row.

## Out of scope

- **Run comparison / diff API** and a re-processing runner — still future work
  (as in the `raw-scrape-pipeline` DESIGN). This endpoint returns the single
  at-scrape-time run linked from the recipe, not a run history.
- **List/archive endpoint** over all raw scrapes or runs.
- **The UI build itself** (separate repo) — this repo ships the enabling API and
  the contract only.
- **Light theme** for the view — Prism is deliberately dark-committed.
- **Per-stage timing** — not captured by the pipeline; the view shows only
  token usage and counts that the stored row actually supports.
