# Librarian Grounded Vetting — Design Spec

**Date:** 2026-07-23
**Status:** Approved by Andrew
**Repos:** `kaleidoscope` (backend)
**Fulfils:** the "Phase 2: Pluggable source retrieval" section of
`plans/2026-07-12-personal-recommender/DESIGN.md`
**Depends on:** the workflow Postgres job queue
(`plans/2026-07-16-workflow-postgres-job-queue/`) for async execution

## Purpose

The Interest Curation Librarian invents the URLs it shelves. The Discover step
asks an LLM for candidate resources with a `url` field
(`scoring/agents.clj:348`), but the request carries **no tools and no web
search** (`workflows/llm_executor.clj:144`) — so `claude-opus-4-6` produces
every URL from training memory. Goodreads book IDs and YouTube video IDs are
exactly what a model confabulates: correct URL *shape*, wrong or dead *target*.
Nothing in `shelve!` (`api/curation.clj`) ever fetches or verifies a link, and
the relevance score that gates shelving is self-reported by the same model, so
a hallucinated resource can score 8/10 and reach the shelf. (The recent
fenced-JSON parse fix, `ddeace5`, made this *visible* — before it, parsing threw
and shelves came up empty, masking the hallucination.)

**Goal:** the Librarian may act on its own authority, but it must **read the
real resource before shelving it** — confirming the resource exists, is live,
and actually matches its claimed title and the reader's taste. Content access is
pluggable: multiple crawlers (homespun CLJ, Anthropic, Firecrawl) sit behind one
seam, and each pipeline stage is independently swappable. Every stage persists
its inputs and outputs so a run is fully auditable and can be resumed or re-run
from any stage. A user-facing feedback loop lets the reader guide future runs.

## Decisions (settled with Andrew)

| Decision | Choice |
|---|---|
| Autonomy model | **User-triggered, trusted to shelve** (no approval gate) + a feedback loop that shapes future runs |
| Vetting bar | **Exists *and* content matches** — fetch the resource, read it, confirm claimed title + taste match |
| Approach | **Grounded, client-orchestrated** — Kaleidoscope drives the stages; Anthropic web tools are one provider, not the whole loop |
| Stage shape | **Functions over data**, not single-method protocols; a fetch **registry + explicit policy**, not a `handles?`-probe chain-of-responsibility |
| Request model | **Async** — kick off, return a run-id, watch it fill; rides the workflow Postgres job queue |
| Shelf shortfall | **Over-discover once; a thin pool yields a thin shelf.** No top-up/backfill loop (YAGNI) |
| Source of truth | One audit table records every candidate's fate; the funnel is derived at read time, not stored |
| Relevance | Decided once, in `vet`, from fetched content; the discovery self-report does not survive the stage |
| Auditability | Every stage persists its **inputs and outputs** as immutable, append-only records — a full, reproducible trace |
| Restart | **Resume failed + re-run from any stage** — reuse persisted upstream artifacts; each re-run is a new append-only attempt with downstream invalidation |

## The pipeline

Grounded discovery replaces hallucinated URLs; the deterministic tail gains a
vetting stage before thresholding. The workflow shape (`Refine Interest` →
`Discover Resources`) is unchanged; vetting slots into the `shelve!` tail.

```
Discover Resources step ──► candidates            (GROUNDED: real URLs, not guessed)
                                 │
     ┌───────────────────────────┴─ vet-candidates ──────────────────────────┐
     │  per candidate, bounded-concurrent:  fetch → parse → vet               │
     │  persists each stage's input+output (curation_stage_runs, append-only); │
     │  projects the candidate head (curation_run_candidates) as it resolves    │
     └───────────────────────────────────┬──────────────────────────────────┘
                                          ▼  (vetted pool: data)
                                   select-shelf (PURE)
                                          ▼
                          guarded archive + create (the only effect left)
```

Two behavioural changes fall out:

- **Relevance comes from `vet`,** not the discovery LLM — closing the
  "hallucinated-but-scores-8/10" hole.
- **The shelved `url` is the fetch stage's `:final-url`** (post-redirect,
  canonical), not the discovered string.

### The data (plain Malli maps — the stable contract)

```clojure
candidate {:title :source :kind :ref {:url} :why}
fetched   {:status :ok|:dead|:error :final-url :raw :content-type :fetcher}
content   {:text :title :author :available? :meta}
verdict   {:matches? :relevance :why :est-time :reason}
```

### Stage functions

```clojure
(defn discover [taste config]  -> [candidate])
(defn fetch    [ref  config]   -> fetched)      ; registry + policy (below)
(defn parse    [fetched]       -> content)      ; host-dispatch multimethod
(defn vet      [content taste] -> verdict)      ; LLM (+ optional heuristic pre-filter)
```

### Fetch — registry + explicit policy (the earned abstraction: ≥3 crawlers)

```clojure
(def fetchers                          ; keyword -> (ref -> fetched); open by assoc
  {:http      #'http/fetch             ; homespun CLJ
   :anthropic #'anthropic-web/fetch    ; Anthropic web_fetch, via llm-executor
   :firecrawl #'firecrawl/fetch
   :mock      #'mock/fetch})

;; policy is DATA from config; tries each in order, first :ok wins, failures are data
;; KALEIDOSCOPE_CURATION_FETCH_POLICY=firecrawl,http
(defn fetch [ref {:keys [fetch-policy] :as config}]
  (or (->> fetch-policy
           (map (fn [k] ((fetchers k) ref)))
           (some (fn [{:keys [status] :as r}] (when (= :ok status) r))))
      {:status :dead :ref ref}))
```

Order is visible in config, not in registration order or exception flow.
Failure crosses the boundary as `{:status …}` data, never a thrown exception.

**Upgrade tripwire:** a fetcher is one operation, so a function + registry map
is correct today. Only if a fetcher grows a *set* of operations that must vary
together (`fetch` + `healthcheck` + `cost-estimate` + `supported-content-types`)
does a `defprotocol Crawler` earn its keep. Until then, functions.

### Parse — host dispatch (the other real multiplicity)

Backend selection (*which crawler*) and source specialization (*what kind of
page*) are separate axes, so they are separate constructs. `fetch` never knows
about YouTube; `parse` never knows about Firecrawl.

```clojure
(defmulti parse (fn [fetched] (host-of (:final-url fetched))))
(defmethod parse "youtube.com"   [f] ...)   ; oEmbed → :available? for removed videos
(defmethod parse "goodreads.com" [f] ...)   ; book metadata → title/author match
(defmethod parse :default        [f] ...)   ; readability / html->text
```

### Discover & vet — stay concrete

`discover` is grounded Anthropic `web_search` (its own `pause_turn` loop lives
*inside that one function*). `vet` is one LLM call over fetched content, with an
optional cheap heuristic pre-filter. Neither gets a registry until a second real
implementation exists — per-stage YAGNI, not a uniform framework.

Grounding makes candidates good to begin with; `fetch → parse → vet` remains the
authoritative gate, because a search result can 404 by shelve-time and a live
page can still be the wrong target.

### Pure/effectful split

```clojure
(defn vet-candidates [candidates config]   ; EFFECTS: bounded-concurrent fetch→parse→vet;
  ...)                                       ; persists each stage's input+output as append-only
                                             ; curation_stage_runs; projects the head; returns the vetted pool

(defn select-shelf [pool config]            ; PURE: sort by relevance, drop dead/off-taste/below-threshold,
  {:shelved ... :dropped ... :disposition}) ; (take shelf-size), decide :swapped | :kept-prior
```

`select-shelf` is unit-tested with plain maps — no mocks. `shelve!` becomes
thin: `vet-candidates` → `select-shelf` → the one remaining effect (guarded
archive + create) → mark the run.

## Async run lifecycle

```
POST …/interests/:id/curate
  → create/attach a workflow run (status queued)
  → enqueue a job on the workflow Postgres job queue
  → return {run-id, status "queued"} immediately

worker: Refine → Discover(grounded) → vet-candidates → select-shelf → guarded swap → mark run

GET  …/curation/runs/:run-id   → status + derived funnel + per-candidate report
```

- The clarify pause survives: if `Refine Interest` needs input the run goes
  `awaiting_input` and the watch endpoint surfaces the questions.
- Failure: the job fails → run status `failed` with the error; the prior shelf
  is untouched. Because every completed stage is persisted, restart resumes from
  the last incomplete stage rather than from scratch (see **Auditability &
  restart**).

## Shelf shortfall policy (explicit)

1. **Over-discover once** — ask discovery for `ceil(shelf-size × over-discover-factor)`
   (default ×2.5) so survivors typically ≥ shelf-size in one pass.
2. **Min-viable-shelf guard** — widen the existing shelf-swap guard: today it
   refuses to swap only when survivors are *empty* (`seq selected`); change it to
   refuse when survivors `< min-viable` (default 3). A healthy shelf is never
   replaced by a meagre one; the prior shelf stands and the shortfall is
   reported. Disposition is recorded as `kept-prior`.
3. **No top-up loop.** A thin pool yields a thin shelf — a reported fact, not a
   retried failure. (Add backfill later only if runs show chronic shortfall.)

## Data model

Reuse `workflow_runs` for run state. Two new tables; one additive column. All
are additive, nullable-where-possible, and drift-tolerant — the prod
`recommendations` shape has drifted from repo migrations, so the migration must
not assume an exact match. The two tables mirror the codebase's existing
`workflow_runs → step_runs` shape: `curation_run_candidates` is the per-candidate
**head** (final outcome), `curation_stage_runs` is the per-stage **trace**
(inputs + outputs). The head is derived from the terminal stage.

### `curation_stage_runs` (new — the trace; enables audit + restart)

Append-only, one row per `(run, candidate, stage, attempt)`. This is what makes
the pipeline auditable and restartable:

| Column | Notes |
|---|---|
| `id`, `run_id`, `candidate_id`, `hostname` | tenant-scoped |
| `stage` | `discover` \| `fetch` \| `parse` \| `vet` |
| `attempt` | monotonic; a re-run appends a new attempt, never overwrites |
| `status` | `pending` \| `running` \| `ok` \| `error` |
| `input`, `output` | **inputs and outputs.** Small structured data (parsed `content`, `verdict`) inline as JSONB; large blobs (raw fetched HTML) go to the pluggable object-storage backend (`plans/2026-07-18-object-storage-model/`), **content-addressed by hash** — the row holds `{storage-ref, sha256, size}`. Content addressing dedupes identical fetches and makes the trace verifiable. |
| `provenance` (JSONB) | `fetcher`, `model-id`, `prompt-version`, `parser-version`, **taste-profile snapshot**, token usage, `started-at`/`finished-at`, `error` — enough to reproduce the stage |

Because the input *and* the config/version are captured, re-running a stage over
its persisted input is directly comparable to the prior attempt — the payoff of
the pure/effectful split. The current value for a `(candidate, stage)` is the
latest `ok` attempt.

### `curation_run_candidates` (new — the head)

Keyed by run-id, one row per *discovered* candidate (shelved **and** dropped);
its `outcome`/`relevance`/`final_url` are a projection of the candidate's
terminal stage:

| Column | Notes |
|---|---|
| `id`, `run_id`, `hostname` | tenant-scoped |
| `ref` (JSONB), `kind`, `source` | the candidate |
| `fetcher`, `final_url`, `relevance` | vetting provenance |
| `outcome` | candidate-level: `shelved` \| `dead` \| `off-taste` \| `below-threshold` |
| `reason` | plain-language ("YouTube video removed", "different book") |
| `created_at` | written incrementally as each candidate resolves |

`outcome` is **candidate-level only.** Run-level `disposition`
(`swapped` \| `kept-prior`) lives on the run, not smuggled into `outcome`.

### `recommendations` (additive column)

A minimal denormalization for the shelf card read path — `final_url`,
`relevance`, `why` (via a nullable JSONB `vetting` column written through the
`persistence/rdbms.clj` JSON helpers; H2-round-trip compatible). Not the whole
verdict duplicated — the audit row is the record.

### Funnel — derived, not stored

`frequencies` over `curation_run_candidates.outcome` at read time. Materialize
only if a read path is measured slow.

## Auditability & restart

Every stage is a durable, append-only fact (`curation_stage_runs`), so a run is
a resumable state machine rather than an ephemeral function call. Restart rides
the workflow Postgres job queue with idempotency keys — a re-enqueue never
double-processes a stage.

**`POST …/curation/runs/:run-id/restart {from-stage?}`** with two modes:

- **Resume (no `from-stage`)** — re-enqueue the run; stages already `ok` are
  skipped and their persisted outputs reused. A resume after a crash/timeout
  never re-fetches what already succeeded. Covers recovery.
- **Re-run from a stage** (`from-stage ∈ discover|fetch|parse|vet`) — invalidate
  that stage and everything downstream *as data* (not by deleting — a new
  `attempt` supersedes), reuse the persisted upstream artifacts by hash, and
  re-execute forward. This is the audit/dev capability: re-vet the whole pool
  with a new prompt (reusing persisted `content`, no re-fetch), or replay `parse`
  after fixing a parser (reusing persisted raw HTML). The taste-profile snapshot
  and `prompt-version` in each attempt's provenance make "why did the verdict
  change?" answerable.

Invariants:

- **Append-only.** Re-runs create attempt N+1; prior attempts remain for audit.
  Nothing is overwritten, so the full history of every candidate is queryable.
- **Idempotent.** Each `(run, candidate, stage, attempt)` job carries an
  idempotency key; a re-delivered job is a no-op if that attempt already
  completed.
- **Terminal-attempt semantics.** The head (`curation_run_candidates`) and the
  shelf always project the latest `ok` attempt; a re-run that changes a verdict
  re-projects the head and, if it changes the shelf, performs the guarded swap
  again (same min-viable guard).
- **Blob lifecycle.** Structured trace (parsed content, verdicts, provenance)
  is retained for audit; large raw-HTML blobs in object storage are GC-eligible
  after a retention window (config) — a GC'd blob leaves the structured trace
  and hash intact, only disabling a *re-parse-from-raw* replay for that
  candidate.

## Observability (the read model *is* the live progress)

Because each stage writes its `curation_stage_runs` row **as it completes**, one
endpoint serves both the completion channel and the post-hoc report — a frontend
watching `GET …/curation/runs/:run-id` sees each candidate advance stage by stage
(discover → fetch → parse → vet → shelved/dropped, each with a reason). No
separate progress stream.

- **Stage trace** — the report can drill into any candidate's per-stage
  attempts: what each stage received (`input`), produced (`output`), and the
  provenance (fetcher, model, prompt version, timings, tokens, error). This is
  the audit surface *and* the restart surface — "re-run from parse" acts on the
  same rows the report shows.
- **Funnel** returned by the report: `{:discovered :fetched :dead :off-taste
  :below-threshold :shelved :shortfall :fetch-mix :latency-ms :disposition}`.
- **OTEL spans** per stage nested under `kaleidoscope.curation.run`
  (`…curation.fetch` attrs: `fetcher`, `host`, `status`, `final-url`;
  `…curation.vet` attrs: `relevance`, `matches?`).
- **Structured logs** — enrich the existing `shelve!` funnel line (Timbre JSON
  in prod).
- **Counters** — dead-link-rate per fetcher, off-taste-rate per host; the
  signals for reordering `fetch-policy` or adding a source parser.

## Feedback loop

The audit rows are the substrate: a reader reacting to a shelved **or dropped**
item feeds a signal that `interests-api/fold-refinement!` writes into the
editable taste profile; the next run's report shows whether the guidance changed
the outcome. Observability and the feedback loop are one dataset viewed two ways.

## Configuration (few knobs, strong defaults)

| Var | Default |
|---|---|
| `KALEIDOSCOPE_CURATION_FETCH_POLICY` | `anthropic,http` |
| `KALEIDOSCOPE_CURATION_VET_CONCURRENCY` | `6` |
| `KALEIDOSCOPE_CURATION_OVER_DISCOVER_FACTOR` | `2.5` |
| `KALEIDOSCOPE_CURATION_MIN_VIABLE_SHELF` | `3` |
| `KALEIDOSCOPE_CURATION_RAW_BLOB_RETENTION_DAYS` | `30` (raw-HTML blobs GC'd after; structured trace kept) |
| relevance thresholds | existing `quick/standard/rigorous` map |

Selected at boot in `init.env`, same pattern as the other pluggable backends.

## File layout (3-layer, keep files honest)

| Namespace | Role |
|---|---|
| `api/curation.clj` | orchestration; `shelve!` delegates to vetting |
| `api/curation/vetting.clj` | `vet-candidates`, `select-shelf`, funnel accounting |
| `api/curation/providers.clj` | `fetchers` registry, `fetch` policy, `parse` multimethod |
| `curation/http.clj`, `curation/anthropic_web.clj`, `curation/firecrawl.clj` | fetch providers (I/O) |
| `curation/parse/{youtube,goodreads,readability}.clj` | source parsers (I/O) |
| `curation/mock.clj` | mock discover/fetch/vet for offline tests + local dev |
| `persistence/curation.clj` | `curation_run_candidates` + `curation_stage_runs` read/write (append-only attempts, terminal-attempt projection) |
| `http_api/curation.clj` | `POST …/curate` (enqueue), `GET …/curation/runs/:id` (report + stage trace), `POST …/curation/runs/:id/restart` |

Pure orchestration in `api/`; external-service I/O in `curation/` and
`persistence/`. `firecrawl.clj` reuses the existing `api/firecrawl.clj` client.

## Testing

Every stage has a `:mock` provider, so the whole pipeline runs under
`embedded-h2/fresh-db!` with no `ANTHROPIC_API_KEY` and no network.

- **`select-shelf` (pure):** hand it candidate maps → assert shelved set,
  disposition, and the min-viable guard, with zero mocks.
- **`fetch` policy:** stub the registry → assert fallthrough on `:error`,
  first-`:ok`-wins, all-dead → `:dead`.
- **`parse` dispatch:** youtube/goodreads/default → correct extraction and
  `:available?`.
- **Vetting funnel end-to-end:** mock discover yields candidates (some dead, one
  wrong-target, one off-taste) → assert only real, on-taste survivors shelve, and
  the audit rows record every drop with a reason.
- **Regression for the original bug:** a mock candidate with a live-but-wrong URL
  (HTTP 200, mismatched title) is dropped `off-taste`, not shelved.
- **Guard:** an all-dead discovery leaves the prior shelf intact (`kept-prior`).
- **Async lifecycle:** enqueue → worker completes → report reflects funnel;
  clarify pause surfaces questions.
- **Resume:** kill a run mid-vet (mock fetch throws on candidate 4) → restart →
  candidates 1–3 are *not* re-fetched (assert no new fetch stage-runs for them,
  reused by hash) and the run completes.
- **Re-run from stage:** completed run, `restart {from-stage vet}` with a
  swapped mock vet → new `vet` attempts appended (old attempts retained), raw
  fetch/parse rows untouched, head + shelf re-project the new verdicts.
- **Idempotency:** a re-delivered stage job for an already-`ok` attempt is a
  no-op (no duplicate row, no duplicate side effect).
- **Blob GC:** a candidate whose raw blob was GC'd still has its structured
  trace + hash; `restart {from-stage parse}` for it fails cleanly (raw gone)
  while `from-stage vet` (reuses persisted `content`) still works.

## Out of scope (YAGNI)

- **Top-up / backfill re-discovery loop** — over-discover once; revisit only if
  measured shortfall demands it.
- **Agentic variant** — letting the LLM drive fetch/discovery via *client-side*
  tools. Deterministic orchestration first; this stays open as a later option.
- **`defprotocol Crawler`** — only when a fetcher needs multiple co-varying
  operations (see upgrade tripwire).
- **Per-stage registries for `discover` / `vet`** — added when a second real
  implementation of either appears, not before.
- **Approval gate before shelving** — the Librarian is trusted; the feedback
  loop is the correction mechanism.
- **Dry-run / shadow replay** — replaying a completed run from persisted inputs
  into a scratch result *without* touching the live shelf (what-if / regression:
  "would the new vet prompt have shelved differently?"). The persisted
  stage inputs make this a natural later addition, but the shadow-run + result-
  diff machinery is deferred; re-run-from-stage acts on the live run for now.
