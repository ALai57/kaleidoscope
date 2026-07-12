# Design: Raw scrape data as a processing pipeline

**Date:** 2026-07-12
**Status:** Design — pending review

## Goal

Preserve the fullest possible record of every recipe scrape — raw HTML plus the
outputs of each processing stage — so we can re-extract, debug, and build
regression tests from real data. The current `scrape` discards the raw HTML the
moment extraction finishes; nothing raw survives.

Beyond capture, the deeper goal is to reshape scraping into an explicit **data
processing pipeline** whose history is an **immutable provenance log**. Raw
acquisition is preserved independently of processing; processing is a function
over that raw data. Crucially, that function is *versioned* — prompts get
reworded, the JSON-LD extractor gets rewritten — so `content = process(raw)` is
really `content = process[version](raw)`, and a given `process[version]` will
not exist next year. Each pipeline run is therefore recorded as a historical
fact: "pipeline version V, run over raw R at time T, produced facts F and content
C." That makes "try a different technique later, over data we already have" a
matter of adding a row, and makes "did my prompt change help or hurt?" answerable
by diffing runs of different versions over the same raw inputs — not re-fetching
the web.

## The pipeline

Three stages, each producing a typed **artifact**, each tagged with the
**technique** that produced it:

```
  URL
   │
   ▼
┌──────────┐  technique: :direct | :firecrawl
│ ACQUIRE  │ ─────────────────────────────▶  RawScrape
└──────────┘  {request-url, final-url, http-status, fetch-tier, raw-html, fetched-at}
   │
   ▼
┌──────────┐  technique: :json-ld | :llm
│  PARSE   │ ─────────────────────────────▶  ExtractedFacts
└──────────┘  {title, ingredients[], steps[], section-signals, grouping?, servings, times, labels}
   │
   ▼
┌──────────┐  technique: :single-section | :llm-grouping | :pre-grouped
│NORMALIZE │ ─────────────────────────────▶  RecipeContent   ← the app's core model
└──────────┘
```

Two principles drive everything below:

1. **Raw is preserved independently of processing.** `RawScrape` is the durable
   corpus. PARSE and NORMALIZE run over it — a better parser next year re-runs
   against stored HTML with no re-fetch.
2. **Capture what a later code/prompt change makes irreproducible.** Because the
   processing function is versioned, past outputs cannot be regenerated once the
   code that made them is gone — so each stage's artifact *and the identity of
   the function that produced it* are recorded as immutable facts. Storing the
   intermediate `ExtractedFacts` (not just the final `RecipeContent`) is what
   lets you diff two versions at the exact stage they diverge; storing the
   pipeline version and the full LLM request is what makes a stored run
   self-describing rather than an orphaned blob.

### Unified PARSE artifact

Both PARSE techniques emit the **same** `ExtractedFacts` shape, feeding a shared
NORMALIZE stage — no technique shortcuts through the pipeline.

- `:json-ld` emits flat `ingredients`/`steps` + `section-signals` (candidate
  section names, header-like lines). No `grouping`.
- `:llm` emits the same flat lists and may additionally emit `grouping`
  (section name + ingredient/step indices) it inferred in the same call.

NORMALIZE dispatches on what the facts carry, so unification costs no extra LLM
call on the LLM path:

- `grouping` present → `:pre-grouped` (deterministic merge of indices).
- `section-signals` but no `grouping` (the JSON-LD case) → `:llm-grouping` (the
  existing constrained grouping call).
- neither → `:single-section` (deterministic, one unnamed section).

The LLM/index-merge and validation machinery already in `recipe_scraper.clj`
(`valid-grouping?`, `grouping->sections`, `group-sections-with-llm`) is reused
as the NORMALIZE implementations — this is a reorganization, not a rewrite.

## Code structure

The pipeline is kept distinct from its record. **Artifacts** are the values that
flow between stages; the **ledger** is the append-only account of what ran, what
it cost, and how it ended. Conflating them — one `run` map carrying artifacts,
provenance, *and* control-flow state, with each stage typed `(fn [run] -> run)`
— is what tangled the original: every stage had to know the whole context shape,
and `:outcome` doubled as both a short-circuit signal and a stored result.

### Stages are functions between artifact types

Each stage maps its input artifact to its output artifact plus the ledger
entries it alone generated. It never sees prior artifacts, the accumulating
ledger, or the outcome of earlier stages:

```clojure
;; acquire   : Context        -> StageResult   (fetch; SSRF-guarded)
;; parse     : RawScrape      -> StageResult   (json-ld or llm)
;; normalize : ExtractedFacts -> StageResult

;; StageResult on success:
{:artifact  <next-artifact>   ;; RawScrape | ExtractedFacts | RecipeContent
 :technique :json-ld          ;; which strategy ran (a kind, not a version)
 :llm-calls [{...}]           ;; this stage's calls, if any
 :warnings  ["..."]}          ;; this stage's warnings, if any

;; StageResult on failure:
{:outcome :bot-blocked :error-detail {...}}
```

Selection stays specific to each stage — acquire falls back direct→firecrawl on
a bot block, parse tries JSON-LD then LLM, normalize dispatches on the shape of
the facts — but every stage reports its choice uniformly as `:technique`. No
uniform "technique dispatch" abstraction is imposed over three genuinely
different selection mechanisms; only the *tag* is uniform.

### A thin orchestrator owns threading, short-circuit, and the ledger

`run-pipeline` reduces the stages: it feeds each stage's `:artifact` to the next,
folds every stage's `:technique`/`:llm-calls`/`:warnings` into the ledger, and
stops at the first `:outcome`. Nothing but the orchestrator knows the shape of
the whole run. It produces two independent values:

```clojure
;; artifacts — what flowed through the pipeline
{:raw     RawScrape
 :facts   ExtractedFacts     ;; nil if parse failed
 :content RecipeContent}     ;; nil if normalize failed

;; ledger — the immutable provenance record
{:pipeline-version "git-sha"                 ;; identity of the code that ran
 :techniques {:acquire :direct :parse :json-ld :normalize :llm-grouping}
 :llm-calls  [{:purpose :parse :model "..." :request {...} :response {...}}]
 :warnings   ["..."]
 :outcome    :success}       ;; or a failure reason keyword
```

- **Short-circuit is orchestrator logic, not per-stage.** A failing stage returns
  an `:outcome`; the reduce halts; no stage begins by asking "did an earlier
  stage already fail?" The run is **still persisted** on the failure path — that
  is how failed and abandoned scrapes enter the corpus.
- **LLM calls ride out on the StageResult**, not through a threaded context or a
  dynamic var. The full request (system prompt + model + messages) is stored, so
  the technique's *version* is recoverable from the row itself: hash the request
  and a January `:llm` separates cleanly from a July `:llm` even though both are
  tagged `:llm`.
- **`pipeline-version`** (build/git SHA) is the version handle for the
  deterministic stages that carry no request to hash — without it a rewritten
  `parse-json-ld` would leave old and new rows indistinguishable except by
  `created_at`, which dates the run, not the code.

The orchestrator is what `scrape` becomes; a rename to `run-pipeline` is
warranted, since it no longer just scrapes but acquires, parses, normalizes,
persists, and returns.

Persistence happens once, at the end of `run-pipeline`, on both the success and
short-circuited-failure paths: the artifacts value becomes one `raw_scrapes` row
(if we got as far as fetching), the ledger plus `:facts`/`:content` become one
`processing_runs` row, then return.

## Data model

The pipeline's raw/processing split is reflected as two tables plus one link
column. All via a new numbered Migratus `.up.sql` / `.down.sql` pair.

### `raw_scrapes` — immutable acquisition (the corpus)

One row per fetch. Written once; never updated.

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `hostname` | VARCHAR NOT NULL | tenant |
| `request_url` | VARCHAR NOT NULL | URL as submitted |
| `final_url` | VARCHAR | after redirect following (nullable) |
| `http_status` | INT | terminal status (nullable — null if never fetched) |
| `fetch_tier` | VARCHAR | `direct` \| `firecrawl` (nullable) |
| `raw_html` | TEXT | captured page (nullable on pre-fetch failure) |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | fetched-at |
| | | `UNIQUE (id, hostname)` — composite FK target |

### `processing_runs` — one pipeline execution over a raw scrape

Many rows per `raw_scrape` (re-processing = new row). The at-scrape-time run is
written now; a re-processing runner is future work (see Out of scope).

| Column | Type | Notes |
|---|---|---|
| `id` | UUID PK | |
| `hostname` | VARCHAR NOT NULL | tenant |
| `raw_scrape_id` | UUID NOT NULL | FK `(raw_scrape_id, hostname)` → `raw_scrapes (id, hostname)` ON DELETE CASCADE |
| `pipeline_version` | VARCHAR NOT NULL | build/git SHA — identity of the code that ran; version handle for deterministic stages |
| `techniques` | JSONB | `{acquire, parse, normalize}` technique *kinds* (not versions — see `pipeline_version` + `llm_calls`) |
| `facts` | JSONB | the `ExtractedFacts` artifact, incl. `labels` (nullable on early failure) |
| `content` | JSONB | the `RecipeContent` artifact (nullable on failure) |
| `llm_calls` | JSONB | array of `{purpose, model, request, response}`; full request stored so prompt+model (the technique version) are recoverable by hashing |
| `warnings` | JSONB | array of strings |
| `outcome` | VARCHAR NOT NULL | `success` \| failure reason (`bot-blocked`, `no-recipe-found`, …) |
| `error_detail` | JSONB | exception message/data on failure (nullable) |
| `created_at` | TIMESTAMPTZ NOT NULL DEFAULT now() | |
| | | `UNIQUE (id, hostname)` — composite FK target |

`content` + `facts.labels` + `techniques` + `warnings` reconstruct the
`ScrapeResult` returned to the client — labels live in `facts` as their single
source of truth (no separate column), so "the extraction result" is a view over
the row, not a redundant copy.

### `recipes.scrape_processing_run_id` — lineage link

New nullable column on `recipes`, FK `(scrape_processing_run_id, hostname)` →
`processing_runs (id, hostname)` **ON DELETE SET NULL**. Nullable because
manually-created recipes have no scrape; `SET NULL` so deleting a run never
cascades away a recipe (and the corpus outlives recipe edits). Full lineage:
`recipe → processing_run → raw_scrape → raw HTML`.

## Layer changes

- **`persistence/scrape_pipeline.clj`** (new): `create-raw-scrape!`,
  `create-processing-run!` (insert, return id), and `get-*` readers by
  id+hostname. Uses `rdbms` JSON/JSONB helpers. *persistence only.*
- **`api/recipe_scraper.clj`**: reorganized into pure ACQUIRE / PARSE / NORMALIZE
  stages (each `input-artifact -> StageResult`) plus a thin `run-pipeline`
  orchestrator (the renamed `scrape`) that threads artifacts, folds the ledger,
  short-circuits on failure, and stamps `pipeline-version`. It gains `:database`
  and `:hostname` in its context, persists the raw scrape + run, and returns
  `ScrapeResult` augmented with `:scrape-processing-run-id`. On a `:type :scrape`
  failure it persists the failed run (with `:outcome` and `:error-detail`) and
  re-throws, so the handler's existing 422 mapping is unchanged.
- **`api/recipes.clj`**: `create-recipe!` accepts and persists
  `scrape-processing-run-id`.
- **`http_api/recipes.clj`**: `/scrape` handler passes `hostname` and the
  `:database` component into the scraper context; `/` POST threads
  `scrape-processing-run-id` through. No new read endpoint (retrieval via
  DB/psql for now — YAGNI).

## Model / schema changes (`models/recipes.cljc`)

- New `ExtractedFacts` Malli schema (validated at the PARSE→NORMALIZE boundary).
- New `RawScrape` Malli schema (validated before persistence).
- `ScrapeResult` gains `[:scrape-processing-run-id :uuid]`.
- `CreateRecipeRequest` gains `[:scrape-processing-run-id {:optional true} [:maybe :uuid]]`.
- `GetRecipeResponse` gains `[:scrape-processing-run-id {:optional true} [:maybe :uuid]]`
  so the UI can see the lineage.

## Frontend contract (`kaleidoscope-ui`, separate repo)

`POST /recipes/scrape` response includes `scrape-processing-run-id`; the UI must
echo it in the subsequent `POST /recipes`. Small, additive change; documented
here so it isn't missed.

## Testing

- **persistence** (`scrape_pipeline_test`): raw-scrape and processing-run
  round-trips on embedded H2, including JSONB fields and a large `raw_html`;
  the composite-FK link from a run to its raw scrape.
- **scraper** (`recipe_scraper_test`, extended): each stage produces its
  artifact with the right technique tag; the JSON-LD path records
  `:normalize :llm-grouping`, the pre-grouped LLM path records `:pre-grouped`
  with populated `llm_calls`; a fetch/extract failure persists a run with the
  correct `:outcome` and no `content`; every persisted run carries a non-null
  `pipeline_version` and each `llm_calls` entry stores the full request (system
  prompt + model), so the technique version is recoverable from the row; the
  returned `:scrape-processing-run-id` resolves to a stored run whose
  `raw_scrape_id` resolves to stored HTML.
- **http** (`recipes_test`, extended): `/scrape` response carries
  `scrape-processing-run-id`; creating a recipe with it persists the FK and it
  round-trips via GET.

## Out of scope (foundations only)

- No archive read/list API.
- No re-processing runner (the table shape enables it; building the batch
  re-run and technique-comparison tooling is later work).
- No retention/cleanup job. At personal-site volume the corpus is small; revisit
  if `raw_scrapes` grows.
