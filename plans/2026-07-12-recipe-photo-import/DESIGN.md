# Design: Import recipe from photo

Let users create a recipe by uploading photo(s) of an offline source — a printed
cookbook page or a phone screenshot — instead of pasting a URL. The upload is
transcribed and structured into a draft the user reviews and edits before saving,
exactly like the existing URL-scrape flow.

## Goals & scope

- **In scope:** clean printed text — printed cookbook pages and phone
  screenshots (recipe apps, notes, Instagram, PDFs). Multiple images per import
  (a recipe spanning two pages; ingredients + method on separate screenshots).
- **Out of scope (YAGNI):** handwritten recipe cards (a later Google Vision
  concern), retaining the uploaded image bytes, server-side image resizing, and
  any async/queue processing. Each is a documented later upgrade, not v1.

## Key decision: OCR and interpretation are separate stages

Transcription (reading pixels into text) and interpretation (structuring text
into a recipe) are **two distinct concepts, and we keep them physically
separate.** This is the central design commitment, and it makes the photo path
converge with the existing URL pipeline instead of duplicating it:

```
URL scrape:    fetch HTML  →                html→text ┐
                            └→ PARSE json-ld          │
photo import:  receive imgs → OCR → transcript ───────┼→ interpret text → facts
                                                      ┘   (shared LLM PARSE)
                                                          → NORMALIZE → ScrapeResult
```

- **OCR stage (new):** image(s) → transcript **text**. Nothing more. It does not
  decide "is this a recipe?" or impose structure — those are interpretation
  judgments. It just reads the page.
- **Interpretation stage (reused):** transcript text → `ExtractedFacts`. This is
  the *same* text-to-facts LLM step the URL pipeline already runs on
  `html->text` output. The photo path reuses it verbatim; no second copy.

Because OCR is now a single, honest concept — "images → text" — it sits behind a
narrow `ImageTranscriber` protocol with genuinely two implementations on one real
axis of variation (**OCR quality/cost**):

- **`claude-vision` (default):** a Claude multimodal call whose prompt asks *only*
  for a verbatim transcription — no interpretation. Reuses the existing Anthropic
  client (`llm/post-anthropic-sync`); cheap on `claude-haiku-4-5`; no new vendor
  or SSRF surface.
- **`google-vision` (committed second impl):** Google Cloud Vision
  `DOCUMENT_TEXT_DETECTION` for handwriting / dense layouts when those come into
  scope (AWS Textract is the documented alternative).

Both produce text; interpretation downstream is identical regardless of which
transcriber ran. That is what makes the protocol boundary earn its keep — real
polymorphism on the OCR axis, not a wrapper around one function. The mock impl is
a test seam, not the justification.

## Architecture

Photo-import adds one new acquisition path; **everything from interpretation
onward is the existing pipeline.** Acquisition is decomplected from processing:
an acquirer produces a `RawSource` **value**, and a single pipeline consumes it —
the pipeline never knows whether the source came from a URL or a photo.

```clojure
;; The value produced by ACQUIRE, consumed by the one pipeline:
RawSource {:source-kind  :url | :photo
           :raw-content  string        ; raw HTML (url) or transcript (photo)
           :request-url  string?        ; url only
           :final-url    string?        ; url only
           :http-status  int?           ; url only
           :fetch-tier   string?        ; url only — how hard we worked to fetch
           :provenance   {:techniques {...} :llm-calls [...] :warnings [...]}}
```

There is **one** `run-pipeline`, not a `run-photo-pipeline`. Acquisition is the
polymorphic part; interpretation, normalization, provenance, and the review-draft
flow are shared.

## Components (respecting the 3-layer separation)

### `api/image_transcriber.clj` (new) — the OCR protocol

Named for what it is: it transcribes images. It does not "extract recipes."

```clojure
(defprotocol ImageTranscriber
  (transcribe [this images]
    "images: [{:content-type string :bytes byte[]}] ->
     {:transcript string :technique :claude-vision|:google-vision :llm-calls [...]}
     The transcript may be empty; deciding whether it contains a recipe is the
     interpretation stage's job, not the transcriber's."))
```

Return shape is a single kind — a transcript plus provenance. It has no
success/failure union: OCR that reads nothing returns an empty/near-empty
transcript, and the *interpretation* stage renders the `:no-recipe-found`
verdict (exactly where the URL path already makes that call).

- `ClaudeVisionTranscriber` (`api-key`, `model` default `claude-haiku-4-5`):
  one Anthropic message with N base64 image blocks + a transcription-only prompt.
- `GoogleVisionTranscriber` (committed second impl): `DOCUMENT_TEXT_DETECTION`.
- `MockTranscriber` (canned transcript) for tests and local dev when
  `ANTHROPIC_API_KEY` is absent.

### `api/recipe_scraper.clj` — one pipeline over `RawSource`

- **Decomplect acquisition from the pipeline.** Introduce `acquire-url` (today's
  fetch path) and `acquire-photo` (validate images → `transcribe` → transcript),
  each producing a `RawSource` value. The existing `process` becomes a function
  of a `RawSource`, not of `url`/`fetcher`.
- **Reuse interpretation.** Split today's `parse-with-llm` into `html->text`
  (url-only preprocessing) and `parse-text` (text → `ExtractedFacts`, shared).
  `PARSE` dispatches on `:source-kind`: `:url` tries JSON-LD then
  `parse-text (html->text …)`; `:photo` runs `parse-text` on the transcript
  directly. The `:no-recipe-found` outcome lives here for **both** sources.
- **One orchestrator.** `run-pipeline` takes a `RawSource` (from whichever
  acquirer), runs `PARSE → NORMALIZE`, and persists provenance. The existing URL
  entry point becomes `acquire-url` piped into `run-pipeline`; the photo entry
  point is `acquire-photo` piped into the same `run-pipeline`.

### `http_api/recipes.clj` — the endpoint

`POST /recipes/scrape-photo` (multipart), reusing the `file-upload?` +
`wrap-multipart-params` precedent from `http_api/photo.clj`. Builds the ctx with
`:image-transcriber` from components, runs `acquire-photo` → `run-pipeline`, and
returns the same `ScrapeResult` body and the same 422 failure mapping as
`/recipes/scrape`.

### `init/env.clj` — wiring

New `kaleidoscope-image-transcriber` boot instruction:

- `KALEIDOSCOPE_IMAGE_TRANSCRIBER_TYPE` = `mock` | `claude-vision` | `google-vision`
- `claude-vision` uses `ANTHROPIC_API_KEY` + an optional model override (default
  `claude-haiku-4-5`); `google-vision` uses its own credentials.
- Exposed as component `:image-transcriber`, injected into the handler ctx.

## Data flow & provenance (reusing the existing tables — with honest names)

Per import: one `raw_scrapes` row + one `processing_runs` row, same as a URL
scrape. The provenance concept genuinely unifies; the columns must stop lying:

- **`raw_scrapes.raw_content`** (renamed from `raw_html`) holds the raw acquired
  source — HTML for a URL scrape, the transcript for a photo import. No image
  bytes are stored anywhere; the transcript is the retained "extracted text."
- **`raw_scrapes.source_kind`** (new): `"url"` | `"photo"` — *what the source is*,
  a different fact from how it was fetched.
- **`raw_scrapes.fetch_tier`** stays about fetching only (`"direct"` /
  `"firecrawl"`); it is `null` for a photo import (nothing was fetched).
  `request_url` / `final_url` / `http_status` are likewise `null` for photos.
- `processing_runs.techniques` records the decomplected pipeline as data, e.g.
  `{:acquire :claude-vision, :parse :llm, :normalize :single-section}` — the OCR
  engine is the acquire technique.

### `ScrapeResult`: expose the `techniques` map, not a flattened string

The current `:extraction-method` string (`"json-ld"`, `"llm"`,
`"json-ld+llm-sections"`) crams two orthogonal axes (source × technique) into one
opaque token. **Replace it** by surfacing the `techniques` map itself
(`{:acquire, :parse, :normalize}`) so the client gets the data and can derive any
label it wants. This changes the existing URL `ScrapeResult` too — intentional,
since both paths share the contract.

### Result/outcome shapes are explicit and schematized

Stage results already carry a success shape (`:artifact` + provenance) or a
failure shape (`:outcome` + `:error-detail`). Give both variants named Malli
schemas in `models/recipes.cljc` so the two shapes are visible types, not a
"which keys are present?" convention.

### Schema change (migration required)

One numbered `.up.sql` / `.down.sql` pair against `raw_scrapes`:

1. Rename `raw_html` → `raw_content`.
2. Add `source_kind text` (backfill existing rows to `'url'`; `NOT NULL` after).
3. Relax `request_url` (and `fetch_tier` if `NOT NULL`) to nullable for photos.

`RawScrape` in `models/recipes.cljc` updates to match: `raw-content` +
`source-kind`, with `request-url` / `final-url` / `http-status` / `fetch-tier`
nullable.

## Input validation & security

- No fetch ⇒ **no SSRF surface**. Images live in-memory (multipart tempfiles),
  are passed only to the transcriber, and are never persisted to disk or storage.
- Handler-enforced limits: ≤5 images per import, ≤5 MB each, content-type ∈
  {`image/jpeg`, `image/png`, `image/webp`, `image/gif`} (Anthropic's supported
  set). Violations → 422 with a clear reason.
- No server-side resizing in v1 — an oversized image gets a clear 422 and the
  client resizes.

## Error handling (parity with `/recipes/scrape`)

- No images / unsupported type / too large → 400/422.
- Interpretation finds no recipe (empty/garbage transcript, or unparseable
  structuring) → `:no-recipe-found` → 422, with a failed `processing_run`
  persisted (matching `run-pipeline`'s failure path). Note this verdict is made
  in the interpretation stage, uniformly for URL and photo.
- Transcriber transport errors (Anthropic, or Google Vision) propagate to the
  Bugsnag exception-reporter middleware, unchanged.
- No `ANTHROPIC_API_KEY` locally ⇒ the `mock` transcriber returns a canned
  transcript, so the flow is exercisable without a live call.

## Testing (every layer; recipe tests run on embedded-postgres)

- `api/image_transcriber_test`: mock + a realistic Claude-vision response fixture
  → asserts the transcript is returned verbatim and multi-image input is handled.
- `api/recipe_scraper_test`:
  - `parse-text` interpretation shared by both sources (same text → same facts).
  - photo pipeline with an **injected mock transcriber** (no network): asserts
    `raw_scrapes` (`source_kind="photo"`, `raw_content=transcript`,
    `fetch_tier=null`) and the `processing_runs` ledger
    (`techniques {:acquire :claude-vision …}`) are written on **success and
    failure** (empty transcript → `:no-recipe-found` failed run).
- `http_api/recipes_test`: end-to-end multipart `POST /recipes/scrape-photo` →
  `ScrapeResult` (with `techniques` map); then a create-recipe round-trip
  carrying `scrape-processing-run-id` (mirrors the existing scrape→create lineage
  test); plus 400/422 error cases (no image, unsupported type, no-recipe-found).

## Files touched

| File | Change |
|---|---|
| `src/kaleidoscope/api/image_transcriber.clj` | **new** — `ImageTranscriber` protocol, `ClaudeVisionTranscriber`, `MockTranscriber` (Google Vision impl stubbed for the committed follow-up) |
| `src/kaleidoscope/api/recipe_scraper.clj` | decomplect acquisition into `acquire-url` / `acquire-photo` → `RawSource`; one `run-pipeline`; split `parse-with-llm` into `html->text` + shared `parse-text`; `PARSE` dispatches on `:source-kind` |
| `src/kaleidoscope/http_api/recipes.clj` | add `POST /recipes/scrape-photo` multipart route |
| `src/kaleidoscope/models/recipes.cljc` | `RawScrape` → `raw-content` + `source-kind`, url fields nullable; `ScrapeResult` exposes `techniques` map (drops flattened `extraction-method`); named success/failure schemas |
| `src/kaleidoscope/init/env.clj` | `kaleidoscope-image-transcriber` boot instruction + `:image-transcriber` component |
| `resources/migrations/*` | rename `raw_html`→`raw_content`, add `source_kind`, relax `request_url`/`fetch_tier` nullability |
| `test/kaleidoscope/api/image_transcriber_test.clj` | **new** |
| `test/kaleidoscope/api/recipe_scraper_test.clj` | shared `parse-text`; photo pipeline success + failure coverage |
| `test/kaleidoscope/http_api/recipes_test.clj` | end-to-end + error cases |
