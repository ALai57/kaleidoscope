# In-Process Image Resize (in-memory queue + fast-fail self-heal) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Revision 2026-07-20 (owner decision + three review rounds).** Mental model: **resize in the background off an in-memory queue; if a rendition is still missing when it's requested, regenerate it on the 404 — synchronously when the resizer is free, else queue it and serve the original for that one request.** No AWS pipeline, no durable DB queue. This revision replaces the prior "fire-and-forget future per upload + unconditional synchronous 404 heal" with a single **resize gate** (one bounded in-memory queue + worker + shared permit) and a **fast-fail** serve path — closing the restart-loop / thread-starvation hole the third review found, and folding in its other fixes (classified negative cache, double-checked existence, safe raw fallback, `media:reconcile` backfill, measured concurrency).

**Goal:** Replace the disabled AWS SNS→SQS→Lambda image resizer with in-process resizing — renditions (`thumbnail` 100×100, `gallery` 165×165, `monitor` 1920×1080, `mobile` 1200×630) generated at `media/<photo-id>/<name>.<ext>` in the same media store as the raw. Uploads enqueue a background resize; a rendition GET that would 404 regenerates it (fast-fail synchronous, else queued). No AWS, no DB during resize.

**Architecture:** A pure resize core (`resize-to`). A **resize gate** component built once at boot: a **bounded in-memory queue** drained by **one worker** (the async workhorse), plus a shared **`Semaphore(MAX-CONCURRENT)`** that both the worker and any synchronous heal acquire — so at most `MAX-CONCURRENT` decodes run at once regardless of upload/view bursts (the memory bound on the tiny box). Two entry points: `enqueue-warm!` (non-blocking, used by upload and by a busy serve path) and `heal-or-enqueue!` (serve path: `tryAcquire` the permit for a short `ACQUIRE-TIMEOUT-MS` → if free, resize this rendition synchronously and serve it; if busy, enqueue it and serve the raw for this request). The single actual decode (`resize-one!`) **double-checks existence after acquiring the permit** (no duplicate work under a stampede), and classifies failures: **permanent** (undecodable / over `MAX-SOURCE-PIXELS`) go in a small bounded **negative cache** so a poison image isn't re-decoded on every view; **transient** (store blip, timeout) are *not* cached, so the self-heal isn't blinded. Nothing touches the DB during resize → Neon scale-to-zero is untouched. Every attempt logs its outcome + emits an OTEL span. The durable convergence backstop is **`task media:reconcile`**, extended to compute the missing-rendition set (`referenced − stored`) and enqueue those onto the gate — so a rendition never generated (never viewed, warm lost to a restart) is swept in off the hot path.

**Tech Stack:** Clojure, `net.coobird/thumbnailator` (pure-Java, ImageIO, subsampled reads), `java.util.concurrent` (`LinkedBlockingQueue`, `Semaphore`, one worker thread), the existing `DistributedFileSystem` media store, OTEL (`span/with-span!`), Kaocha + matcher-combinators (embedded-h2 / in-memory) **including concurrency tests**. **No migration, no persistence layer, no reaper.**

## Global Constraints

- **Simple model, one mechanism:** the *resize gate* (queue + worker + permit) is the single place decodes happen. Upload and a busy serve path `enqueue-warm!`; an idle serve path decodes synchronously through the same permit. There is no second async path (no per-upload `future`).
- **Bounded decodes = memory safety.** `Semaphore(MAX-CONCURRENT)` caps concurrent decodes. `MAX-CONCURRENT` is a `def` **derived from measured peak decode heap** (`(max 1 (quot heap-budget peak-rendition-bytes))`), with a boot `assert`, **not** a config env var. The prior "512 MB peak ⇒ N=1" was an unmeasured guess; the box is 1 GB / `-Xmx512m` and a *subsampled* 1080p decode peaks in the tens of MB, so **measure and likely set 2** (Task 2 Step 0). Keep the arithmetic in code, not a comment.
- **Never block the hot path on a decode.** The serve path uses `tryAcquire(ACQUIRE-TIMEOUT-MS ≈ 250 ms)`, distinct from the decode `RESIZE-TIMEOUT-MS`. A cold gallery after a restart must not park web threads on the permit (Jetty pool is 50; `/ping` shares it; Fly health-check times out at 10 s → a synchronous herd could restart-loop). Busy ⇒ enqueue + serve raw, never wait.
- **Never fully decode at native resolution.** Thumbnailator reads subsampled; a header-only dimension read rejects sources over `MAX-SOURCE-PIXELS` *before* any decode.
- **Double-checked existence.** `resize-one!` re-checks the object exists *after* acquiring the permit — concurrent requests for the same cold rendition collapse to one decode.
- **Classify failures.** Permanent (bad source) → bounded negative cache (skip re-decode). Transient (store anomaly / timeout / permit-miss) → **not** cached (retry on next trigger). Never let a momentary blip blind the self-heal.
- **No DB during resize** → scale-to-zero preserved. **Idempotent** (overwrite same key). **Raw is ground truth** (written synchronously); renditions are recomputable.
- **3-layer separation:** `api/resize.clj` = pure core + the gate + `resize-one!`/`enqueue-warm!`/`heal-or-enqueue!`; `http_api/photo.clj` calls `heal-or-enqueue!`; store access behind `DistributedFileSystem`. **Every change ships with tests**, incl. concurrency. Keep `docs/operations.md` current.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `deps.edn` | add `net.coobird/thumbnailator` | Modify |
| `src/kaleidoscope/api/resize.clj` | pure `resize-to`/`RENDITIONS`/`source-pixels`; the resize gate (queue+worker+permit+neg-cache); `resize-one!`, `enqueue-warm!`, `heal-or-enqueue!`; `missing-renditions` | Create |
| `src/kaleidoscope/api/albums.clj` | `new-image`: raw-first, drop notify/`write-location`, `enqueue-warm!` after rows commit | Modify |
| `src/kaleidoscope/http_api/photo.clj` | `serve-photo`: rendition 404 → `heal-or-enqueue!` | Modify |
| `src/kaleidoscope/http_api/http_utils.clj` | a `no-store` response variant for the raw fallback (no rendition ETag) | Modify |
| `src/kaleidoscope/init/env.clj` | build the gate at boot; `KALEIDOSCOPE_IMAGE_RESIZER_TYPE` (`in-process` \| `none`) | Modify |
| `src/kaleidoscope/tasks/reconcile.clj` + `scripts/media/reconcile` | add the `referenced − stored` missing-rendition set + `enqueue-warm!` backfill | Modify |
| `fly.toml`, `scripts/ephemeral/deploy-app` | resize config replaces `IMAGE_NOTIFIER_TYPE=none` | Modify |
| tests: `api/resize_test.clj` (+ concurrency), `api/albums_test.clj`, `http_api/photo_test.clj` | pure core, gate, stampede, fast-fail, upload, serve heal | Create/Modify |
| `docs/operations.md` | model, memory bound, fast-fail, negative cache, reconcile backfill, AWS retirement | Modify |

---

## Task 1: Pure resize core — `resize-to`, `RENDITIONS`, pixel guard

**Files:** Create `src/kaleidoscope/api/resize.clj`; test `test/kaleidoscope/api/resize_test.clj`.

**Interfaces (all PURE):**
- `RENDITIONS` = `{"thumbnail" [100 100] "gallery" [165 165] "monitor" [1920 1080] "mobile" [1200 630]}`.
- `MAX-SOURCE-PIXELS` = `100000000`.
- `source-pixels [^bytes raw-bytes]` → `long` from the header only (`ImageIO/getImageReaders` → `.getWidth/.getHeight`, no `read`); throws on undecodable input.
- `resize-to [^bytes raw-bytes ext ^long w ^long h]` → `^bytes` — one rendition; Thumbnailator subsampled decode + aspect-fit (`.size(w,h).keepAspectRatio(true)`), format from `ext`.

- [ ] **Step 1: Failing test** (no I/O): `resize-to` for each `RENDITIONS` entry on `public/images/example-image.png` → non-empty bytes decoding to `w'≤w ∧ h'≤h`; `source-pixels` → the fixture's pixel count.
- [ ] **Step 2: Run → FAIL. Step 3: Implement** (add `net.coobird/thumbnailator {:mvn/version "0.4.20"}`). **Step 4: PASS. Step 5: Commit.**

---

## Task 2: The resize gate + `resize-one!` + `enqueue-warm!`

**Files:** extend `api/resize.clj`; extend `resize_test.clj`.

**Interfaces (Produces):**
- `MAX-CONCURRENT` — `(max 1 (quot HEAP-BUDGET-BYTES PEAK-RENDITION-BYTES))` over named `def`s, with a boot `(assert (>= HEAP-BUDGET-BYTES PEAK-RENDITION-BYTES))`. `ACQUIRE-TIMEOUT-MS` 250, `RESIZE-TIMEOUT-MS` 60000, `QUEUE-CAPACITY` 512, `NEG-CACHE-MAX` 1000.
- `make-resize-gate [store]` → a `resize-gate` value `{:store :queue (LinkedBlockingQueue. QUEUE-CAPACITY) :permit (Semaphore. MAX-CONCURRENT) :neg-cache (atom {}) :worker …}`. Starts `MAX-CONCURRENT` worker thread(s): loop `task = queue.take()` → for each category `(with-permit gate (resize-one! …))` (blocking acquire; worker is a background thread, blocking is fine) → each catches `Throwable` (a bad task must never kill the loop). `stop!` on the gate for shutdown.
- `resize-one! [gate photo-id category ext]` — **PRECONDITION: caller holds a permit.** (1) if the rendition object exists → `{:outcome :hit}` (double-check — collapses same-key stampede). (2) if negative-cached permanent → `{:outcome :skipped-bad-source}`. (3) read `media/<id>/raw.<ext>`; `(> (source-pixels raw) MAX-SOURCE-PIXELS)` → permanent fail → neg-cache → `{:outcome :failed :kind :permanent}`; else `resize-to`, `put-file`, `{:outcome :made :bytes …}`. Wrap the decode in `RESIZE-TIMEOUT-MS`; **release the permit only when the decode thread actually finishes** (an uninterruptible decode holds its slot until done — do not release early or two decodes run at once). Classify exceptions: undecodable → permanent (neg-cache); store/timeout → transient (**not** cached). Log INFO/ERROR + OTEL span event `(photo-id category outcome)` every attempt.
- `enqueue-warm! [gate photo-id ext] & categories` — non-blocking `offer` of a task (default all `RENDITIONS`) onto the queue; drop-if-full (the serve 404 / reconcile backstop catches drops). Used by upload, the busy serve path, and reconcile.

- [ ] **Step 0:** measure peak decode heap for a 1920×1080 subsample on a representative large raw; set `PEAK-RENDITION-BYTES` from it (round up generously). Record the number + method in a comment.
- [ ] **Step 1: Failing tests** (in-memory store + an injectable gate):
  - *sequential:* seed `media/<id>/raw.png`; a permit-held `resize-one!` for `"gallery"` → `:made`, object exists, fitted; a second → `:hit` (spy: `resize-to` ran **once**).
  - *permanent fail:* a header over `MAX-SOURCE-PIXELS` → `:failed :permanent`, neg-cached; a second call → `:skipped-bad-source` (no decode).
  - *transient not cached:* raw missing → transient fail, **not** neg-cached (a later call re-attempts).
  - **stampede (concurrency):** fire K threads at `resize-one!` for the *same* cold key through one shared permit(1) → exactly **one** decode (spy), all K observe the object. (Deterministic via a latching in-memory store double.)
  - `enqueue-warm!` on a seeded raw → the worker eventually creates all four objects (poll).
- [ ] **Step 2–5:** implement, run, commit.

---

## Task 3: Enqueue warm on upload — `new-image`

**Files:** Modify `api/albums.clj`; update `albums_test.clj`.

**Interfaces:** `new-image`: compute `raw-key`; **`put-file` the raw FIRST** (fail → error, no rows); create photo + version rows; **after they commit, `enqueue-warm!` the gate** (threaded through `components` as `resize-gate`, so `none`/tests use a no-op gate). Remove the `notify-image-resizer!` component + the `write-location`/`s3://…` block + `:message-attributes`. (Grep `write-location`; delete it + `photo_resize_contract_test` if now unused.)

- [ ] **Step 1: Update failing test** — after `new-image`: raw exists, and (with a real in-memory gate) the four rendition objects eventually appear; the version rows exist. Remove `s3://…`/attribute assertions.
- [ ] **Step 2–5:** thread `resize-gate`; raw-first; enqueue after rows; delete the notify block (+ contract test if unused); run; commit.

---

## Task 4: Serve-path fast-fail self-heal — `serve-photo`

**Files:** Modify `http_api/photo.clj`; add a `no-store` helper to `http_utils.clj`; update `photo_test.clj`.

**Interfaces:**
- `hu/adapter-response-no-store [adapter request]` (or a flag) — like `adapter-response` but emits `Cache-Control: no-store` and **no** ETag, for the raw fallback (so a momentary raw served at a rendition URL can't poison the CDN under the rendition key).
- `heal-or-enqueue! [gate photo-id category ext]` → one of `{:made bytes}` / `:busy` / `:no-raw` / `:bad-source`. If the raw is absent → `:no-raw`. Else `tryAcquire(ACQUIRE-TIMEOUT-MS)`: **got it** → `resize-one!` (permit held) → `{:made bytes}` on success, `:bad-source` on permanent fail, `:busy`-style transient handled as enqueue; **didn't get it** → `enqueue-warm! gate photo-id ext category` → `:busy`.
- `serve-photo`: after resolving the version `path` and attempting the store: if not-found AND `category` ∈ `RENDITIONS` AND the media store is the gate's store, call `heal-or-enqueue!`:
  - `{:made bytes}` → **200** serving those bytes (no re-read).
  - `:busy` → **200** serving the **raw** via `adapter-response-no-store` (image renders, unoptimized, fills on next view).
  - `:bad-source` / `:no-raw` → the existing **`not-found`** + the ERROR log already emitted (breakage is *visible*, not masked).
  Non-rendition paths and hits are unchanged.

- [ ] **Step 1: Failing tests:**
  - seed DB rows + store with **only the raw**; `GET …/gallery.png` on an **idle** gate → **200**, the `gallery` object now exists; a second GET → 200 hit (no re-decode).
  - **burst (concurrency):** hold the permit busy (occupy the gate), fire K distinct-key rendition GETs → **none blocks past `ACQUIRE-TIMEOUT-MS`**; each returns 200 with the raw (`no-store`, no ETag) and enqueues; the worker later creates them.
  - rendition whose raw is also missing → `not-found`.
- [ ] **Step 2–5:** implement; verify the raw-fallback response carries `no-store` + no ETag; run; commit.

---

## Task 5: Gate wiring + config + deploy

**Files:** `init/env.clj`, `fly.toml`, `scripts/ephemeral/deploy-app`; test `env_test.clj`.

- [ ] **Step 1:** `env.clj` — `KALEIDOSCOPE_IMAGE_RESIZER_TYPE`: `in-process` (default) → build one `make-resize-gate` over the media store at boot, place it in the system as `:resize-gate` (upload enqueues, serve heals). `none` → **no resizer at all**: a no-op gate whose `enqueue-warm!` does nothing and whose `heal-or-enqueue!` returns `:no-raw` (warm off *and* heal off — one unambiguous meaning). Shutdown hook calls `stop!`.
- [ ] **Step 2:** `fly.toml` — replace `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE="none"` + comment with `KALEIDOSCOPE_IMAGE_RESIZER_TYPE="in-process"`. `deploy-app` — swap the secret.
- [ ] **Step 3–5:** test `in-process` yields a working gate, `none` a no-op; run; commit.

---

## Task 6: Durable convergence via `media:reconcile` + docs

**Files:** `src/kaleidoscope/tasks/reconcile.clj`, `scripts/media/reconcile`, `docs/operations.md`.

- [ ] **Step 1:** extend `reconcile` to compute **`missing = referenced − stored`** (version-row keys whose object is absent — the opposite of the existing `orphans = stored − referenced`) restricted to rendition categories, and — in `--apply` mode — `enqueue-warm!` (or directly `resize-one!` off the hot path) each affected photo. This is the durable answer to "which renditions are missing?" and the sweep that converges best-effort → complete without a DB queue. Pure `missing`-set test (no S3).
- [ ] **Step 2:** `docs/operations.md` — document the model (background queue + fast-fail 404 heal), the memory bound (`MAX-CONCURRENT` measured + `Semaphore` + subsampled + `MAX-SOURCE-PIXELS`), **no DB during resize → scale-to-zero**, the fast-fail (`ACQUIRE-TIMEOUT-MS`) + "first gallery load after a deploy regenerates renditions in the background and serves originals briefly", the classified negative cache, the log/OTEL observability, `media:reconcile` as the missing-rendition sweep/backfill, and retirement of the AWS resizer (`../image-resizer`).
- [ ] **Step 3:** run `task media:verify-resize` against an ephemeral env as the acceptance gate. Commit.

---

## Decisions

1. **One resize gate; fast-fail on 404; no durable queue (owner decision).** The workhorse is an in-memory queue drained by a worker; a 404 regenerates synchronously **only if the resizer is free**, else it enqueues and serves the original. Simple to hold in the head, and — unlike the prior unconditional synchronous heal — a cold gallery after a restart cannot park web threads and trigger a health-check restart loop (the third review's critical finding). The raw is ground truth (synchronous); renditions are recomputable; `media:reconcile` is the durable convergence backstop.
2. **Fast-fail is the whole safety story on the hot path.** `tryAcquire(ACQUIRE-TIMEOUT-MS)` distinct from the decode timeout means a request never waits on a busy resizer; at most `MAX-CONCURRENT` decodes ever run; the serve path degrades to "serve the original, fill in later," never to thread-starvation.
3. **`MAX-CONCURRENT` is measured, derived data — not a knob and not a guess.** `⌊heap-budget / measured-peak-decode⌋`, boot-asserted. The old "peak = 512 MB ⇒ 1" was unfounded (subsampled 1080p peaks in tens of MB on a 1 GB box); measure and likely run 2, which also shrinks the cold-gallery tail.
4. **Negative cache holds only *permanent* failures, bounded.** A transient blip/timeout must never blind the self-heal for a TTL window — that would defeat the design's recovery premise. Permanent = a property of the raw (undecodable / over `MAX-SOURCE-PIXELS`), effectively a memoized `bad-source?`; transient isn't cached. Bounded to `NEG-CACHE-MAX` (no leak).
5. **Double-checked existence.** Existence is re-checked *after* acquiring the permit, so N concurrent requests for the same cold rendition collapse to one decode.
6. **Raw fallback is `no-store`, and only for the *busy* (transient) case.** Serving the raw at a rendition URL is a graceful stopgap only if it can't poison caches — so `Cache-Control: no-store`, no rendition ETag (`adapter-response`'s normal ETag/cache-control would pin the unoptimized image under the rendition key). Genuine failures (`:bad-source`/`:no-raw`) serve `not-found` + the ERROR log — breakage stays visible.
7. **Timeout must not break the memory bound.** ImageIO decodes may be uninterruptible; the permit is released only when the decode thread finishes, so a timed-out decode never runs concurrently with a fresh one. Stated as an assumption to verify (Task 2).
8. **Observability = log + OTEL span + the reconcile sweep.** Per-attempt outcome logs/spans (ensure worker-thread ERRORs reach the appender, not just stdout — the worker is outside Bugsnag's request scope). The authoritative "what's missing" answer is `media:reconcile`'s `referenced − stored` set (Task 6), since logs record only *attempts* and the neg-cache is in-memory.
9. **AWS resizer retired.** `resize-to` + `RENDITIONS` reproduce the Go Lambda's `imaging.Fit` + dims; after prod resize is verified, tear down `../image-resizer`'s Terraform (follow-up).

## Self-Review

- **Spec coverage:** in-process resize replacing the Lambda (Tasks 1–4) ✅; in-memory queue + worker as the async workhorse (Task 2) ✅; **fast-fail synchronous self-heal on 404** (Task 4, Decisions 1–2) ✅; measured bounded concurrency + subsampled + pixel guard (Tasks 1–2, Decision 3) ✅; scale-to-zero (no DB in resize) ✅; classified bounded negative cache (Task 2, Decision 4) ✅; double-checked existence / stampede (Task 2 test, Decision 5) ✅; safe raw fallback (Task 4, Decision 6) ✅; timeout/permit-release (Decision 7) ✅; log/OTEL + `media:reconcile` missing-rendition backfill (Tasks 2/6, Decision 8) ✅; config (`none` = fully off), deploy, AWS retirement, acceptance gate (Tasks 5–6, Decision 9) ✅.
- **Concurrency is tested, not asserted:** same-key stampede (Task 2) and distinct-key burst / no-block-past-`ACQUIRE-TIMEOUT` (Task 4), via a latching store double.
- **Placeholder scan:** pure tests, gate behavior, and constants (`MAX-SOURCE-PIXELS`, `ACQUIRE-TIMEOUT-MS`, `RESIZE-TIMEOUT-MS`, `QUEUE-CAPACITY`, `NEG-CACHE-MAX`) have values; `PEAK-RENDITION-BYTES`/`MAX-CONCURRENT` are set from a Task 2 Step 0 measurement; `<photo-id>` etc. are runtime.
- **Type consistency:** `RENDITIONS` dims = `albums/IMAGE-VERSIONS` = the Go resizer; `resize-one!`/`enqueue-warm!`/`heal-or-enqueue!`/`make-resize-gate`/`missing-renditions` names consistent across Tasks 2–6; `heal-or-enqueue!` outcomes (`:made`/`:busy`/`:no-raw`/`:bad-source`) are exactly what `serve-photo` branches on.
