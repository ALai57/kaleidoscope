# In-Process Image Resize (on-demand + eager warm) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Revision 2026-07-20 (owner decision, post two rounds of Hickey/van Rossum/Fowler review).** The durable Postgres job-queue approach is **dropped**. Chosen design: **best-effort in-memory resize + self-healing on 404 + a clear success/failure log.** The 404 self-heal is what makes "best-effort" safe — a rendition lost to a restart, or never generated, is regenerated on first view. This dissolves the queue's hard problems (retry-stranding, signal contract, reaper, dual-engine DDL) rather than fixing them, and is right-sized for a single-owner CMS. van Rossum's "ship the small version" + Fowler's "the broken image repairs itself" + the memory arithmetic, combined.

**Goal:** Replace the disabled AWS SNS→SQS→Lambda image resizer with in-process resizing — renditions (`thumbnail` 100×100, `gallery` 165×165, `monitor` 1920×1080, `mobile` 1200×630) generated **on-demand-and-cached** at `media/<photo-id>/<name>.<ext>` in the same media store as the raw, with a **best-effort eager warm** at upload so common views are pre-generated. No AWS pipeline, no DB queue.

**Architecture:** A pure resize core (`resize-to`). An idempotent, effectful **`ensure-rendition!`** — "if the object is missing, read the raw, resize, write it" — called by two paths: (1) an **eager warm** fired async after upload (best-effort; if the process dies before it runs, no harm); (2) the **serve path**, synchronously, when a rendition GET would 404 (**self-heal** — this is the durability net *and* it closes the window where a version-row URL exists before its object). Both paths share **one `Semaphore(1)`**, so at most one image is ever decoded at a time — the memory bound on the 1 vCPU / `-Xmx512m` box (with subsampled decode + a pre-decode pixel guard). **Nothing touches the DB during resize** (only S3), so Neon scale-to-zero is untouched. Each attempt logs success/failure (structured + an OTEL span) — the clear record, in place of a durable failure table. A small in-memory **negative cache** prevents a poison image from being re-decoded on every view.

**Tech Stack:** Clojure, `net.coobird/thumbnailator` (pure-Java, ImageIO, subsampled reads), `java.util.concurrent.Semaphore`, the existing `DistributedFileSystem` media store, OTEL (`span/with-span!`), Kaocha + matcher-combinators (embedded-h2 / in-memory), Fly.io. **No migration, no persistence layer, no worker/reaper.**

## Global Constraints

- **Best-effort + self-heal, not durable queue.** Eager resize may be lost (restart) — that is acceptable *because* the serve path regenerates on 404. Correctness of "a rendition eventually exists" rests on the serve-path heal, not on the eager warm.
- **One decode at a time.** A single process-wide `Semaphore(1)` (`resize/permit`) guards every decode — eager and serve — so peak heap is one 1920×1080 decode regardless of concurrent uploads/views. `MAX-CONCURRENT` is a `def` with the ⌊`-Xmx` / peak-rendition-heap⌋ arithmetic in a comment — **not** a config env var (its only non-default value OOMs the box).
- **Never fully decode at native resolution.** Thumbnailator reads subsampled; a header-only dimension read rejects sources over `MAX-SOURCE-PIXELS` *before* any decode (decompression-bomb / absurd-upload backstop).
- **No DB during resize** → Neon scale-to-zero preserved. Resize touches only the object store.
- **Idempotent.** `ensure-rendition!` overwrites the same key; a half-warmed photo self-completes on view.
- **3-layer separation:** `api/resize.clj` = pure core + effectful `ensure-rendition!` + eager submit; `http_api/photo.clj` calls the serve-path heal; store access behind `DistributedFileSystem`. No HTTP in `api/`.
- **Same renditions/keys/store as the old Lambda.** The `photo_versions` rendition rows already exist (created by `new-image`); resize only produces the objects.
- **Every change ships with tests** (embedded-h2 / in-memory). Keep `docs/operations.md` current.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `deps.edn` | add `net.coobird/thumbnailator` | Modify |
| `src/kaleidoscope/api/resize.clj` | pure `resize-to` + `RENDITIONS` + `source-pixels` guard; effectful `ensure-rendition!` (semaphore, timeout, negative-cache, log+span); `warm-photo!` (async best-effort eager) | Create |
| `src/kaleidoscope/api/albums.clj` | `new-image`: raw-first, drop the notify/`write-location` block, fire `warm-photo!` after the rows commit | Modify |
| `src/kaleidoscope/http_api/photo.clj` | `serve-photo`: on a missing rendition (raw present), `ensure-rendition!` synchronously then serve; else current behavior | Modify |
| `src/kaleidoscope/init/env.clj` | `KALEIDOSCOPE_IMAGE_RESIZER_TYPE` (`in-process` \| `none`) → the `warm`/`ensure` fns or no-ops | Modify |
| `fly.toml`, `scripts/ephemeral/deploy-app` | resize config replaces the `IMAGE_NOTIFIER_TYPE=none` line | Modify |
| tests: `api/resize_test.clj`, `api/albums_test.clj`, `http_api/photo_test.clj` | pure core + ensure + eager + self-heal coverage | Create/Modify |
| `docs/operations.md` | on-demand+eager model, memory bound, self-heal, logging, AWS-resizer retirement | Modify |

---

## Task 1: Pure resize core — `resize-to`, `RENDITIONS`, pixel guard

**Files:** Create `src/kaleidoscope/api/resize.clj`; test `test/kaleidoscope/api/resize_test.clj`.

**Interfaces (all PURE):**
- `RENDITIONS` = `{"thumbnail" [100 100] "gallery" [165 165] "monitor" [1920 1080] "mobile" [1200 630]}` (mirrors `albums/IMAGE-VERSIONS` + the old Go resizer).
- `MAX-SOURCE-PIXELS` = `100000000` (100 MP backstop).
- `source-pixels [^bytes raw-bytes]` → `long` from the image **header only** (`ImageIO/getImageReaders` → `.getWidth/.getHeight`, no `read`). Throws on undecodable input.
- `resize-to [^bytes raw-bytes ext ^long w ^long h]` → `^bytes` — one rendition, Thumbnailator subsampled decode + aspect-fit (`.size(w,h).keepAspectRatio(true)`), output format from `ext` (jpg q≈0.85 / png).

- [ ] **Step 1: Failing test** (no I/O): `resize-to` on a fixture (`public/images/example-image.png`) for each `RENDITIONS` entry → non-empty bytes, decodes to `w'≤w ∧ h'≤h`; `source-pixels` → the fixture's actual pixel count.
- [ ] **Step 2: Run → FAIL.** **Step 3: Implement** (add `net.coobird/thumbnailator {:mvn/version "0.4.20"}`). **Step 4: Run → PASS.** **Step 5: Commit.**

---

## Task 2: `ensure-rendition!` — idempotent, bounded, logged

**Files:** extend `api/resize.clj`; extend `resize_test.clj`.

**Interfaces (Produces):**
- `permit` — a process-wide `(Semaphore. 1)`; `MAX-CONCURRENT` = 1 (`def` + arithmetic comment).
- `RESIZE-TIMEOUT-MS` (e.g. 60000).
- `ensure-rendition! [store photo-id category ext]` → `{:status :hit|:made|:failed}` (+ serves-caller the object on `:made`/`:hit`). Behavior:
  1. If the rendition object already exists (`fs/get-file` not-missing) → `:hit`, done (no decode, no permit).
  2. Negative-cache check: if `(photo-id, category)` failed within `NEG-CACHE-TTL-MS` → `:failed` fast (no decode). Prevents re-decoding a poison image on every view.
  3. Else acquire `permit` (bounded), under a `RESIZE-TIMEOUT-MS` deadline: read `media/<photo-id>/raw.<ext>` from `store`; guard `(> (source-pixels raw) MAX-SOURCE-PIXELS)` → throw; `resize-to` for `category`'s dims; `put-file` to `media/<photo-id>/<category>.<ext>`. Release permit.
  4. Success → log INFO + OTEL span event `(photo-id category :made)` → `:made`. Failure/timeout → log ERROR + OTEL span event `(photo-id category :failed err)` + record in negative cache → `:failed`. Never throws to the caller (both paths handle a `:failed` gracefully).
- `warm-photo! [store photo-id ext]` — fire-and-forget `(future …)` that calls `ensure-rendition!` for all four categories (best-effort eager warm; the shared permit serializes it against serve-path heals). Catches `Throwable` (a warm failure must never crash the request or a thread).

- [ ] **Step 1: Failing tests** (in-memory store):
  - store seeded with `media/<id>/raw.png`, no renditions → `ensure-rendition!` for `"gallery"` returns `:made` and the object now exists, fitted; a second call returns `:hit` (idempotent, no re-decode — assert via a counter/spy that `resize-to` ran once).
  - raw missing → `:failed`, no throw; a second immediate call is `:failed` fast (negative cache, `resize-to`/`get` not re-attempted).
  - `source-pixels > MAX-SOURCE-PIXELS` → `:failed`, no decode.
  - `warm-photo!` on a seeded raw → eventually all four rendition objects exist (deref the future / poll).
- [ ] **Step 2–5:** implement (permit, timeout via a cancellable future, a small `atom` negative cache with TTL, OTEL span events), run, commit.

---

## Task 3: Eager warm on upload — `new-image`

**Files:** Modify `src/kaleidoscope/api/albums.clj`; update `albums_test.clj`.

**Interfaces:** `new-image`: compute `raw-key`; **`put-file` the raw FIRST** (fail → error, no rows); create the photo + version rows; **after they commit, call `warm-photo!`** (via a `resize-warm!` fn threaded through `components`, so `none`/tests stub it). **Remove** the `notify-image-resizer!` component, the `write-location`/`s3://…` message block, and `:message-attributes` — there is no resizer URL contract now. (Grep `write-location`; if `new-image` was its only consumer, delete it + `photo_resize_contract_test`.)

- [ ] **Step 1: Update failing test** — `new-image-test`'s notify assertion becomes: after `new-image`, the raw object exists and `resize-warm!` was invoked with the photo-id (spy); with a real in-memory store + the real `warm-photo!`, the rendition objects eventually appear. Remove the `s3://…`/message-attributes assertions.
- [ ] **Step 2–3:** thread `resize-warm!` through `components`; reorder raw-first; fire warm after the rows exist; delete the notify/`write-location` block (+ `photo_resize_contract_test` if now unused). **Step 4–5:** run focused, commit.

---

## Task 4: Serve-path self-heal — `serve-photo`

**Files:** Modify `src/kaleidoscope/http_api/photo.clj`; update `photo_test.clj`.

**Interfaces:** `serve-photo` (the `/v2/photos/:photo-id/:filename` handler) — after resolving the version row's `path` and attempting to serve from the media store: **if the result is not-found AND `category` ∈ `RENDITIONS` (a rendition, not the raw) AND the raw object exists**, call `ensure-rendition! store photo-id category ext` synchronously, then:
- `:made`/`:hit` → serve the (now present) rendition.
- `:failed` → serve the **raw** as a graceful fallback if it exists (image shows, unoptimized), else the existing `not-found`.
Non-rendition paths and hits are unchanged. This makes a version-row URL work on first GET even before the eager warm finishes, and heals any rendition lost to a restart.

- [ ] **Step 1: Failing test** — seed the DB with a photo + version rows and the store with only the **raw** (no `gallery` object). `GET /v2/photos/<id>/gallery.<ext>` → **200** (self-healed), and the `gallery` object now exists in the store. A second GET → 200 `:hit` (no re-decode). A GET for a rendition whose raw is also missing → `not-found` (or raw-fallback path asserted absent). Drive `serve-photo` directly (as the existing photo tests do).
- [ ] **Step 2–5:** implement the missing-rendition branch (reuse `hu/adapter-response`; call `ensure-rendition!`), run, commit.

---

## Task 5: Config + deploy + docs

**Files:** `src/kaleidoscope/init/env.clj`, `fly.toml`, `scripts/ephemeral/deploy-app`, `docs/operations.md`; test `env_test.clj`.

- [ ] **Step 1:** `env.clj` — boot instruction `KALEIDOSCOPE_IMAGE_RESIZER_TYPE`: `in-process` (default) → real `resize-warm!` (= `warm-photo!` bound to the media store) and the serve heal is active; `none` → `resize-warm!` is a no-op (tests / an env that doesn't resize; the serve heal simply finds no raw or is disabled). Test: `in-process` yields a warm fn that warms; `none` yields a no-op.
- [ ] **Step 2:** `fly.toml` — replace `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE="none"` + its "until SNS migrated off AWS" comment with `KALEIDOSCOPE_IMAGE_RESIZER_TYPE="in-process"`. `deploy-app` — swap the `IMAGE_NOTIFIER_TYPE=none` secret for `IMAGE_RESIZER_TYPE=in-process`.
- [ ] **Step 3:** `docs/operations.md` — document: the on-demand+eager model (best-effort warm at upload, synchronous self-heal on a rendition 404); the memory bound (`Semaphore(1)` + subsampled decode + `MAX-SOURCE-PIXELS`); that **no DB is touched during resize → scale-to-zero preserved**; the negative cache; the success/failure log + OTEL span as the observability surface; and that the AWS SNS/SQS/Lambda resizer (`../image-resizer`) is retired (tear down its Terraform once prod resize is verified).
- [ ] **Step 4:** run `task media:verify-resize` against an ephemeral env (upload ⇒ rendition appears — via warm or the poll's first GET self-heal) as the acceptance gate. Commit.

---

## Decisions

1. **Best-effort in-memory + self-heal, not a durable queue (owner decision).** Two review rounds showed the event-driven queue's durability was undermined by its own best-effort in-memory *trigger* (retry-stranding, lost-signal), collapsing its advantage over the simpler design to "a queryable failure record." Rather than add a timed-wake + signal contract + oldest-pending metric to rescue it, we take the smaller design: eager warm is best-effort; **the serve-path 404 heal is the recovery net** (a missing rendition regenerates on view). This is durable *enough* — the raw (the irreplaceable byte-stream) is always written synchronously; renditions are recomputable and self-heal on demand.
2. **The 404 self-heal doubles as three fixes.** It is (a) the durability net for a warm lost to a restart; (b) the closer of the "version-row URL exists before its object" broken-image window Fowler flagged (the first GET makes the object); (c) a natural cache-fill — renditions exist after first view whether or not the eager warm ran.
3. **One `Semaphore(1)` across both paths — a `def`, not a knob.** Peak heap = one 1920×1080 decode = ⌊512 MB / peak⌋ = 1. van Rossum: don't ship a config env var whose only non-default value OOMs the box.
4. **Synchronous heal in the serve path — bounded, accepted.** A cold rendition GET blocks ~1–2 s for one decode and serializes on the permit under a burst of cold views (slow, never OOM). For a single-owner CMS this is fine; the eager warm keeps the common path already-cached. `RESIZE-TIMEOUT-MS` bounds a hung decode so it can't wedge a request or the permit.
5. **Negative cache stops poison hammering.** A rendition that fails to generate is cached-as-failed for `NEG-CACHE-TTL-MS`, so repeated GETs of a broken image don't re-decode it on every request (which would hold the permit and starve legit resizes). The pixel guard rejects decompression bombs before any decode.
6. **Observability = a clear success/failure log + OTEL span (owner decision), not a failure table.** Each `ensure-rendition!` logs its outcome with `photo-id`/`category`/reason and emits a span event. The worker runs outside Bugsnag's request scope, so ensure that worker/`future` ERRORs reach the log appender (not only stdout). Sufficient for a single owner; no durable `failed` ledger.
7. **Raw-first ordering; residual orphan is reclaimable.** Raw → store first (fail commits nothing), then the photo/version rows. A crash after the raw write leaves an orphan object with no row — detected by `media:reconcile`'s `orphans = stored − referenced` set (the store→DB direction), not lost data.
8. **AWS resizer retired.** After prod resize is verified, tear down `../image-resizer`'s Terraform (SNS/SQS/DLQ/Lambda/IAM). The Go resizer's `imaging.Fit` + dims are preserved by `resize-to` + `RENDITIONS`, so output is equivalent. Teardown is a follow-up.

## Self-Review

- **Spec coverage:** in-process resize replacing the Lambda (Tasks 1–4) ✅; best-effort eager warm (Task 3) ✅; **self-healing on 404** (Task 4, Decisions 1–2) ✅; memory bound — semaphore/subsampled/pixel-guard (Tasks 1–2, Constraints, Decision 3) ✅; scale-to-zero (no DB in resize — Constraints, Decision 6-adjacent) ✅; clear success/failure log + span (Task 2, Decision 6) ✅; negative cache + timeout (Task 2, Decisions 4–5) ✅; raw-first + reconcile backstop (Task 3, Decision 7) ✅; config/docs/AWS retirement + acceptance (Task 5, Decision 8) ✅.
- **Dropped from the prior draft (intentionally):** `media_resize_jobs` table + migration, persistence layer, reaper, backoff/attempts, dual-engine DDL split, the event-driven signal contract, config knobs — all moot without the queue. Recorded in the revision note.
- **Placeholder scan:** pure tests, the ensure/heal behavior, and the constants (`MAX-SOURCE-PIXELS`, `RESIZE-TIMEOUT-MS`, `NEG-CACHE-TTL-MS`, `MAX-CONCURRENT`) have stated values/defaults; `<photo-id>` etc. are runtime.
- **Type consistency:** `RENDITIONS` dims = `albums/IMAGE-VERSIONS` = the Go resizer; `resize-to`/`source-pixels`/`ensure-rendition!`/`warm-photo!`/`resize-warm!` names consistent across Tasks 1–5; `ensure-rendition!` returns `{:status …}` consumed identically by the eager and serve paths.
