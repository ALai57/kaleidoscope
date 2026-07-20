# In-Process Image Resize (Postgres Job Queue) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.
>
> **Revision 2026-07-20 (post three-lens review — Hickey / van Rossum / Fowler).** Changes folded in: idempotency is now a **DB constraint** (nullable `active_photo_id` + plain UNIQUE, mirroring the sibling `workflow_jobs`), not a racy app-code check; enqueue is a real **Transactional Outbox** (raw-to-store first, then one DB tx for photo+versions+job); failures get a **watched surface** (ERROR log + OTEL span event + `failed-count`), since the worker runs outside Bugsnag's request scope; a **pre-decode pixel-budget guard** backstops the subsampled decode; **concurrency is a value** (`:max-concurrent`, default 1 = ⌊Xmx / peak-rendition-bytes⌋), not a shape; **backoff/time is a pure fn** in `api/resize`, not SQL, and uses the sibling's *linear* curve; the resize unit has a **hard timeout** and the **reaper runs independently** of the drain loop (poison-by-hang); a lease-reap (crash) does **not** burn an attempt (only exceptions do); and the **separate-worker-process path is cut** from this plan (added the day a second machine exists). See Decisions.

**Goal:** Replace the disabled AWS SNS→SQS→Lambda image resizer with in-process resizing, driven by a durable Postgres job queue drained by a **bounded** (default single-slot) worker — producing the same renditions (`thumbnail` 100×100, `gallery` 165×165, `monitor` 1920×1080, `mobile` 1200×630) at the same keys (`media/<photo-id>/<name>.<ext>`) in the same media store as the raw, with no AWS pipeline.

**Architecture:** On upload the app writes the raw to the store, then — in **one DB transaction** — inserts the photo row, the version rows, and one `media_resize_jobs` row (a Transactional Outbox: no window where the raw exists with no job, or a job with no raw), and after commit **signals** the worker in-process. The worker is **event-driven, not polling** — it blocks on that signal, so the DB drains to zero and Neon suspends when no uploads are happening (preserving scale-to-zero). On a wake (or at boot) it reaps stalled leases, then claims jobs with `SELECT … FOR UPDATE SKIP LOCKED` and runs up to `:max-concurrent` at a time (**default 1** = ⌊512 MB / one 1920×1080 decode⌋), reading the raw from the media store, resizing with **Thumbnailator (subsampled decode)**, and `put-file`-ing each rendition into the same store the raw lives in — so renditions land in `kal-media-prod` (prod) or `kal-eph-<slug>-media` (ephemeral) automatically. Because the signal is an in-memory hand-off, the worker lives **in the web process** (a separate process could only poll or `LISTEN`, both of which defeat scale-to-zero). Jobs are durable, idempotent (re-run overwrites the same keys), retried with linear backoff; a resize unit has a hard timeout so a hanging decode can't freeze a slot; a crash is healed by the boot reap on the next restart; failures are logged + traced + counted.

**Tech Stack:** Clojure, next.jdbc + HoneySQL (+ `with-transaction`), Migratus (H2/Postgres-portable DDL), `net.coobird/thumbnailator` (pure-Java, ImageIO, subsampled reads), the existing `DistributedFileSystem` media store, OTEL (`span/with-span!`, span events), Kaocha + matcher-combinators (embedded-h2 default; **embedded-postgres for `SKIP LOCKED` runtime SQL**, per `workflows_test.clj`), Fly.io.

## Global Constraints

- **Mirror `plans/2026-07-16-workflow-postgres-job-queue` conventions** — the sibling queue. DDL applies under **both** embedded-H2 and Postgres: **no partial indexes, no `SKIP LOCKED` in DDL**. The "one active job" invariant uses the sibling's portable trick — a plain `UNIQUE INDEX` on a **nullable** column (multiple NULLs don't collide in either engine). Runtime queue SQL (`SKIP LOCKED`, `interval`) is **Postgres-only**, exercised only via `embedded-postgres/fresh-db!`; embedded-H2 tests must never call `claim-next-job!`/`reap-stalled-jobs!`.
- **3-layer separation:** `persistence/media_resize_jobs.clj` = data access only (stores already-computed values — it does **not** compute backoff/time); `api/resize.clj` = pure resize + pure backoff + worker orchestration; enqueue happens in `api/albums.clj` inside the outbox transaction. Store access stays behind `DistributedFileSystem`.
- **Preserve DB scale-to-zero — the worker MUST be event-driven, never idle-polling.** The DB is tuned for Neon scale-to-zero (`env->pg-conn`: `minimumIdle 0`, `idleTimeout 120s` → the pool drains and Neon suspends after ~5 min idle). A worker that polls `claim-next-job!` on a timer holds a connection forever and **kills scale-to-zero.** So: enqueue delivers an **in-process signal**; the worker **drains-to-empty then blocks** on that signal (no DB touch when idle); recovery is **reap-at-boot + reap-on-wake** (not a timed reaper). The DB is touched only during upload-triggered bursts, when it is already awake. This requires the worker to be **in the same process** as the enqueuer (see Decision 2 — a separate worker process would have to poll or hold a `LISTEN` connection, both of which defeat scale-to-zero).
- **Bounded concurrency is a value.** `:max-concurrent` (default 1) = ⌊`-Xmx` / peak single-rendition heap⌋. Single-slot is `N=1`, documented arithmetic — not a shape welded into the loop.
- **Peak heap bound = subsampled decode + a pre-decode pixel-budget guard.** Thumbnailator subsamples so the decoded buffer tracks the largest rendition (1920×1080), not source megapixels; a header-only dimension read rejects decompression-bomb / absurd sources (> `MAX-SOURCE-PIXELS`) *before* any decode.
- **Transactional Outbox.** Raw → store first; then photo + versions + job in **one** `with-transaction`. The only residual failure (whole tx rolls back after the raw is written) leaves an orphan object with no DB trace — reclaimed by the existing `task media:reconcile` sweep, not lost data.
- **Idempotent + durable + observable.** Re-run overwrites the same rendition keys; a crash mid-resize leaves the job reap-able; terminal `failed` emits an ERROR log + OTEL span event + is counted (`failed-count`) — the worker is outside Bugsnag's request scope, so it must signal for itself.
- **Migrations** numbered `…-slug.up/down.sql`, `--;;` separators. **Every change ships with tests.** Keep `docs/operations.md` current.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `resources/migrations/…-add-media-resize-jobs.up/down.sql` | `media_resize_jobs` table: nullable `active_photo_id` + UNIQUE, claimable index | Create |
| `deps.edn` | add `net.coobird/thumbnailator` | Modify |
| `src/kaleidoscope/persistence/media_resize_jobs.clj` | data access only: enqueue (outbox), claim, complete, retry (stores given `available_at`), reap (no attempt burn), fail, counts | Create |
| `src/kaleidoscope/api/resize.clj` | pure `resize-renditions` + `source-pixels`/guard + pure `next-available-at`; effectful `resize-job!` (timeout), `drain-once!` (bounded pool), `run-worker!` (drain + independent reaper), failure signaling | Create |
| `src/kaleidoscope/api/albums.clj` | `new-image`: raw-first, then outbox tx (photo+versions+job); drop the notify/`write-location` URL block | Modify |
| `src/kaleidoscope/init/env.clj` | `KALEIDOSCOPE_IMAGE_RESIZER_TYPE` (`in-process` \| `none`); start ONE in-web worker thread when `in-process` | Modify |
| `fly.toml`, `scripts/ephemeral/deploy-app` | resize config replaces the `IMAGE_NOTIFIER_TYPE=none` line | Modify |
| tests: `persistence/media_resize_jobs_test.clj`, `api/resize_test.clj`, `api/albums_test.clj`, `init/env_test.clj` | queue + resize + outbox + config coverage | Create/Modify |
| `docs/operations.md` | resize queue, worker, config, memory bound, monitoring, AWS-resizer retirement | Modify |

---

## Task 1: Migration — `media_resize_jobs` with a declarative "one active job" constraint

**Files:** Create `resources/migrations/20260720000001-add-media-resize-jobs.up/down.sql`; test `persistence/media_resize_jobs_test.clj`.

**Interfaces (Produces):** table `media_resize_jobs (id, photo_id, active_photo_id, hostname, raw_path, status, attempts, max_attempts, available_at, locked_at, locked_by, last_error, created_at, updated_at)`; `UNIQUE INDEX media_resize_jobs_one_active ON (active_photo_id)` (nullable → many terminal jobs coexist, at most one active per photo — **DB-enforced**, no partial index); `INDEX … ON (status, available_at)`.

- [ ] **Step 1: Failing test** (applies under **both** engines; the nullable-UNIQUE is the guard):

```clojure
(deftest migration-applies-and-one-active-job-per-photo
  (testing "H2 applies the migration (guards non-portable DDL)"
    (let [db  (embedded-h2/fresh-db!)
          pid #uuid "11111111-1111-1111-1111-111111111111"
          j1  (sut/enqueue-job! db {:photo-id pid :hostname "andrewslai.com" :raw-path "media/111/raw.jpg"})
          j2  (sut/enqueue-job! db {:photo-id pid :hostname "andrewslai.com" :raw-path "media/111/raw.jpg"})]
      (is (match? {:status "queued" :attempts 0} j1))
      (is (= (:id j1) (:id j2)))                       ;; idempotent: the UNIQUE index collapses the duplicate
      (sut/complete-job! db (:id j1))
      (let [j3 (sut/enqueue-job! db {:photo-id pid :hostname "andrewslai.com" :raw-path "media/111/raw.jpg"})]
        (is (not= (:id j1) (:id j3)))))))              ;; re-enqueue allowed after done (active_photo_id was NULLed)
```

- [ ] **Step 2: Run → FAIL.** **Step 3: Write migration:**

```sql
CREATE TABLE media_resize_jobs (
  id              uuid NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
  photo_id        uuid NOT NULL,
  active_photo_id uuid,                                  -- = photo_id while active; NULL when done/failed
  hostname        varchar NOT NULL,
  raw_path        varchar NOT NULL,
  status          varchar NOT NULL DEFAULT 'queued',     -- queued | running | done | failed
  attempts        int NOT NULL DEFAULT 0,
  max_attempts    int NOT NULL DEFAULT 5,
  available_at    timestamp NOT NULL DEFAULT now(),
  locked_at       timestamp,
  locked_by       varchar,
  last_error      varchar,
  created_at      timestamp NOT NULL DEFAULT now(),
  updated_at      timestamp NOT NULL DEFAULT now()
);
--;;
CREATE UNIQUE INDEX media_resize_jobs_one_active ON media_resize_jobs (active_photo_id);
--;;
CREATE INDEX media_resize_jobs_claimable ON media_resize_jobs (status, available_at);
```
`down.sql`: `DROP TABLE IF EXISTS media_resize_jobs;`

- [ ] **Step 4: Run → PASS.** **Step 5: Commit** `feat(media): add media_resize_jobs queue (nullable active_photo_id UNIQUE, H2/PG-portable)`.

---

## Task 2: Persistence — data access (stores values; no time/policy)

**Files:** `src/kaleidoscope/persistence/media_resize_jobs.clj`; extend the test.

**Interfaces (Produces):**
- `enqueue-job! [db {:keys [photo-id hostname raw-path]}]` → job. Insert with `active_photo_id = photo_id`; on UNIQUE conflict return the existing active job (`INSERT … ON CONFLICT (active_photo_id) DO NOTHING` then `SELECT`, or the H2 `MERGE` path — mirror the sibling's dual-engine helpers). **Idempotency is the DB's job, not a check-then-insert.**
- `claim-next-job! [db owner]` → job (`running`, `attempts`+1, lock set) or `nil`. Postgres-only `SKIP LOCKED`.
- `complete-job! [db id]` → `status 'done'`, `active_photo_id = NULL`.
- `retry-job! [db id available-at error]` → `status 'queued'`, `available_at = available-at` (**caller-supplied value**), `last_error = error`, `active_photo_id` stays = `photo_id`. Does **not** compute time.
- `fail-job! [db id error]` → `status 'failed'`, `active_photo_id = NULL`, `last_error = error`.
- `reap-stalled-jobs! [db lease-seconds]` → requeue `running` jobs whose `locked_at` < now − lease **without incrementing `attempts`** (a crash/OOM-kill is not the image's fault). Postgres-only.
- `failed-count [db]`, `pending-count [db]`, `get-job [db id]` — observability/tests.

- [ ] **Step 1: Failing tests** — enqueue idempotency + re-enqueue-after-complete (H2 ok, exercises the UNIQUE); `claim-next-job!` hands two queued jobs to two owners via `SKIP LOCKED`, none twice (embedded-postgres); `retry-job!` stores the given `available_at` and increments nothing but is claimable only after it; `fail-job!` NULLs `active_photo_id` (so the photo can be re-enqueued); `reap-stalled-jobs!` requeues an old `running` job **with `attempts` unchanged** (embedded-postgres).
- [ ] **Step 2–5:** implement (mirror the sibling's H2/PG `insert`/`ON CONFLICT` helpers), run (embedded-postgres for the `SKIP LOCKED`/reap paths), commit.

---

## Task 3: Pure core — renditions, pixel guard, backoff

**Files:** `src/kaleidoscope/api/resize.clj`; test `api/resize_test.clj`.

**Interfaces (all PURE — no DB, no store, no clock as hidden state):**
- `RENDITIONS` = `{"thumbnail" [100 100] "gallery" [165 165] "monitor" [1920 1080] "mobile" [1200 630]}`.
- `MAX-SOURCE-PIXELS` (e.g. `100000000` = 100 MP) — the decompression-bomb / absurd-upload backstop.
- `source-pixels [^bytes raw-bytes]` → `long` (width×height) read from the image **header only** (`ImageReader.getWidth/getHeight` via `ImageIO/getImageReaders`, no `read`). Throws on undecodable input.
- `resize-renditions [^bytes raw-bytes ext]` → `{category → ^bytes}`. Thumbnailator with subsampled decode + aspect-ratio fit (`.size(w,h).keepAspectRatio(true)`), `.outputFormat` from `ext` (jpg q≈0.85 / png). Pure bytes→bytes.
- `next-available-at [attempts ^Instant now]` → `Instant` = `now + min(300, 30·attempts) seconds` (**linear, mirroring the sibling** — reconciles the old `2^attempts`). Pure; `now` is passed in, not read from a hidden clock.

- [ ] **Step 1: Failing tests** (no DB/S3):
  - `resize-renditions` on a fixture → 4 categories, each non-empty, each decodes to an image whose w ≤ box.w and h ≤ box.h (fit preserved).
  - `source-pixels` on the fixture → the fixture's actual pixel count.
  - `next-available-at`: `(next-available-at 1 t)` = `t+30s`; `(next-available-at 20 t)` = `t+300s` (capped); pure/deterministic for a fixed `now`.

- [ ] **Step 2: Run → FAIL.** **Step 3: Implement** (add `net.coobird/thumbnailator {:mvn/version "0.4.20"}` to `deps.edn`). **Step 4: Run → PASS.** **Step 5: Commit.**

---

## Task 4: Effectful resize + worker (timeout, bounded pool, independent reaper, signaling)

**Files:** extend `api/resize.clj`; extend `resize_test.clj`.

**Interfaces (Produces):**
- `resize-job! [{:keys [media-store]} job]` — guard: `(when (> (source-pixels raw) MAX-SOURCE-PIXELS) (throw …))`; else read raw from `media-store`, `resize-renditions`, `put-file` each to `media/<photo-id>/<cat>.<ext>` **wrapped in a hard timeout** (`RESIZE-TIMEOUT-MS`, e.g. 60s, via a cancellable future) so a hanging decode frees the slot. Idempotent overwrite. Effectful; returns `{:written [cats]}`.
- `drain-once! [{:keys [database media-store owner max-concurrent] :or {max-concurrent 1}}]` — claim up to `max-concurrent` jobs and run them on a bounded pool of that size; per job: success → `complete-job!`; exception → log ERROR (+ OTEL span event with `photo_id`/error) → `retry-job!` db id `(next-available-at attempts (now))` err, **or** `fail-job!` (+ ERROR + span event + this is the watched terminal signal) when `attempts >= max_attempts`. Returns per-job outcomes.
- `run-worker! [{:keys [database media-store owner lease-seconds max-concurrent signal] :as ctx} running?]` → `{:stop! fn :signal! fn}`. **Event-driven, not polling** (preserves DB scale-to-zero). One loop: at boot, `reap-stalled-jobs!` then drain-to-empty; then **block waiting on `signal`** (a blocking in-process queue / `core.async` channel). On a wake: `reap-stalled-jobs!` (reap-on-wake — cheap, and the DB is already awake from the enqueue), then `drain-once!` repeatedly until it returns "no work", then block again. `signal!` (returned, and handed to the enqueuer) is called after each outbox commit to wake the worker. **No timer, no idle SQL** → the DB suspends between upload bursts. Hung jobs are bounded by `RESIZE-TIMEOUT-MS` (not a reaper); crashed jobs are healed by the boot reap on the next Fly restart. Clean shutdown via `running?` + a poison-pill on `signal`.

- [ ] **Step 1: Failing tests:**
  - `resize-job!` against an in-memory store seeded with `media/<id>/raw.png` → the 4 rendition objects now exist at the right keys, non-empty.
  - a source over `MAX-SOURCE-PIXELS` → `resize-job!` throws (guard fires) before decoding.
  - `drain-once!` (embedded-postgres): enqueue + seed store → job `done`, renditions present; a job whose store lacks the raw → the job is `retried` with a future `available_at` and `attempts` incremented; after `max_attempts` → `failed` and `(failed-count db)` = 1.
  - `next-available-at` is used for the retry delay (assert the stored `available_at` matches).
- [ ] **Step 2–5:** implement (timeout via `deref`+`future-cancel`; note honestly in a comment that ImageIO may not interrupt promptly, so the timeout bounds the *logical* slot + marks retry even if an OS thread lingers), run (embedded-postgres), commit.

---

## Task 5: Enqueue on upload — Transactional Outbox in `new-image`

**Files:** Modify `src/kaleidoscope/api/albums.clj`; update `albums_test.clj`.

**Interfaces:** `new-image` becomes: compute `raw-key`; **`put-file` the raw to the store FIRST** (fail → honest error, nothing committed); then **one `with-transaction`**: `create-photo!`, `create-photo-version-2!`, and `media-resize-jobs/enqueue-job! {:photo-id :hostname :raw-path raw-key}`; then **after the tx commits, call `resize-signal!`** (the worker's `signal!`, threaded through `components`) to wake the event-driven worker. The `notify-image-resizer!` component, the `write-location`/`s3://…` message block, and the `:message-attributes` are all **removed** — the worker reads the store directly, so there is no URL contract. (Grep for `write-location`; if `new-image` was its only consumer, delete it + `photo_resize_contract_test`.) The signal fires *after* commit (not inside the tx) so a rolled-back enqueue never wakes the worker to a non-existent job; and it is a best-effort in-memory nudge — if it's lost, the boot reap + next-upload wake still drains the job (correctness never depends on the signal, only latency does).

- [ ] **Step 1: Update failing test** — `new-image-test`'s notify assertion becomes: after `new-image`, `media_resize_jobs` has exactly one `queued` row for the photo with `raw_path = media/<id>/raw.<ext>`, **and** the photo/version rows exist — proving they committed together. Add a test: if `enqueue-job!` throws inside the tx, the photo/version inserts roll back (no orphan rows), while the raw object remains (reconcilable). Remove the `s3://…`/message-attributes assertions.
- [ ] **Step 2–3:** thread a `resize-enqueue!` fn through `components` (replacing `notify-image-resizer!`); reorder to raw-first + `with-transaction`; delete the notify/`write-location` block (and `photo_resize_contract_test` if `write-location` is now unused). **Step 4–5:** run focused, commit.

---

## Task 6: Config + in-web worker — `init/env.clj`

**Files:** Modify `src/kaleidoscope/init/env.clj`; test `env_test.clj`.

**Interfaces:** boot instruction `KALEIDOSCOPE_IMAGE_RESIZER_TYPE`:
- `in-process` (default) → create **one shared in-process signal** (a bounded blocking queue), start **one** `resize/run-worker!` bound to it (guarded so exactly one starts; shutdown hook stops it via `running?`+poison-pill), and expose `resize-enqueue!` = insert-a-job (called inside the outbox tx) **and** the worker's `signal!` (called by `new-image` after commit). `:max-concurrent` from `KALEIDOSCOPE_RESIZE_MAX_CONCURRENT` (default `"1"`). **The worker and the enqueuer share the same process** so the signal is an in-memory hand-off (the scale-to-zero requirement — see Constraints/Decision 9).
- `none` → no-op enqueue, no worker (tests / an env that doesn't resize).

**No `KALEIDOSCOPE_WORKER_INLINE`, no `main.clj` `"worker"` branch, no separate-process topology** — see Decision 2. The worker loop is a plain fn, so promoting it to its own Fly process later is a config+deploy change, not a code change.

- [ ] **Step 1: Failing test** — `in-process` launcher yields an enqueue fn that writes a `media_resize_jobs` row; `none` yields a no-op. (Worker-thread startup covered by the Task 4 drain integration test, not a live-thread unit assertion.)
- [ ] **Step 2–5:** implement the boot instruction + single-worker startup/shutdown, run, commit.

---

## Task 7: Deploy config + docs

**Files:** `fly.toml`, `scripts/ephemeral/deploy-app`, `docs/operations.md`.

- [ ] **Step 1:** `fly.toml` — replace `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE="none"` + its "until SNS migrated off AWS" comment with `KALEIDOSCOPE_IMAGE_RESIZER_TYPE="in-process"`.
- [ ] **Step 2:** `deploy-app` — swap the `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none` secret for `KALEIDOSCOPE_IMAGE_RESIZER_TYPE=in-process` so ephemeral envs resize into their own bucket.
- [ ] **Step 3:** `docs/operations.md` — document: the `media_resize_jobs` queue + the Transactional Outbox ordering; the **event-driven, in-process worker and why (Neon scale-to-zero)** — signal-on-enqueue, drain-to-empty, reap-at-boot + reap-on-wake, **no idle polling**, so the DB still suspends between upload bursts; the bounded worker (`:max-concurrent` arithmetic on 512 MB) + subsampled decode + `MAX-SOURCE-PIXELS` guard; the linear backoff + `max_attempts` + `RESIZE-TIMEOUT-MS` + lease default (state it, comfortably > `RESIZE-TIMEOUT-MS`); the **monitoring surface** (ERROR log + OTEL span event on terminal fail + `failed-count` — the worker is outside Bugsnag's request scope); why the worker stays in-process (a separate process would break scale-to-zero); and that the AWS SNS/SQS/Lambda resizer (`../image-resizer`) is retired (tear down its Terraform once prod resize is verified).
- [ ] **Step 4:** run `task media:verify-resize` against an ephemeral env as the acceptance gate. Commit.

---

## Decisions

1. **Queue over bounded-inline — with the alternative kept live (and now more tempting).** The review split: a `Semaphore(1)` inline executor (van Rossum) also bounds heap and decouples the request, with no table/reaper/backoff/dual-engine split — and renditions are *recomputable*, so "durability" guards a non-catastrophe. We keep the **queue** because (a) it is the idiom the sibling `workflow-postgres-job-queue` establishes, so a second kind of background work adopting it is the consistent choice, and one *in-process* worker could later drain both; (b) the Transactional Outbox (Decision 4) makes upload→resize integrity a real DB property. **But the scale-to-zero constraint (Decision 9) tips the scale toward the inline cut:** the inline executor touches only S3 during work and adds *zero* idle DB load by construction, while the queue only preserves scale-to-zero via the more delicate event-driven machinery below — and the "consistency with the sibling" argument weakens once you notice the sibling *polls* (and so would itself have to change to respect scale-to-zero). **Reversal is cheap and documented:** collapse to `Semaphore(1)` inline — delete the table/reaper/backoff/signal, keep the pure `resize-renditions` core + the raw-first ordering untouched; missing renditions are found by `media:reconcile`'s `dangling` set. The pure core makes either topology a small change; if you'd prefer to start with the cut, that is a supported and arguably simpler starting point.
2. **In-web worker only; a separate process is not merely YAGNI here — it is incompatible with scale-to-zero.** Beyond the maintenance/cost argument (a `worker` `-main` branch that never runs on a 1-machine deploy and doubles cost if it does), a separate worker process **cannot** get the in-memory enqueue signal, so it would have to poll or hold a Postgres `LISTEN` connection — either of which keeps Neon awake and defeats scale-to-zero (Decision 9). So the worker stays in the web process by design, not just by frugality. Keep it a plain fn (the seam), but do not build the separate-process path.
3. **Idempotency is a DB constraint, not an app-code check.** A read-then-check-then-insert is a race under concurrent enqueue (retried request, caller-supplied `photo-id`). The sibling's nullable-`active_photo_id` + plain `UNIQUE` gives a real DB guarantee that still allows re-enqueue after terminal, with no partial index (H2-portable). This deleted the apologetic app-code comment entirely.
4. **Transactional Outbox.** Raw→store first (irreducible non-transactional seam; a failure here commits nothing), then photo+versions+job in one tx. Eliminates the "raw with no job, forever" orphan Fowler flagged. The only residual — whole tx rolls back after the raw write — is an untracked orphan object reclaimed by `task media:reconcile`, not lost user data.
5. **Backoff/time are values computed in `api/resize`, stored by persistence.** `next-available-at attempts now` is a pure fn (testable with no DB; `now` passed in). Persistence stores the value. This pulls the DB clock and the backoff policy out of the data layer and shrinks the Postgres-only surface. Curve is **linear** `min(300, 30·attempts)`, matching the sibling (reconciles the earlier `2^attempts`).
6. **Concurrency is a value.** `:max-concurrent` default 1 = ⌊`-Xmx512m` / one 1920×1080 decode⌋. Single-slot is `N=1` with arithmetic behind it, not dogma; raising `-Xmx` or the box raises the safe number without touching the loop. (With `N=1` in prod the `SKIP LOCKED` path isn't exercised in prod — the test guards the multi-worker/future-process case.)
7. **Poison handling is complete on both axes.** Poison-by-exception → linear backoff + `max_attempts` → `failed`. Poison-by-hang → `RESIZE-TIMEOUT-MS` bounds the unit so a stuck decode can't freeze the slot. Crash/OOM-kill mid-resize → the **independent** reaper requeues **without burning an attempt** (transient, not the image's fault), so a big photo isn't marched to `failed` by restarts.
8. **Failures are signaled, not just stored.** The worker runs outside Bugsnag's request-scoped middleware, so terminal `failed` emits an ERROR log + an OTEL span event (`photo_id`, `last_error`, attempts) + is counted (`failed-count`). "Queryable" is not monitoring; this gives one surface a human/check actually reads — the dead-letter arm the old SNS→SQS→**DLQ** pipeline had.
9. **Preserve Neon scale-to-zero — the worker is event-driven, never idle-polling.** The DB deliberately drains to zero connections when idle (`env->pg-conn`: `minimumIdle 0`, `idleTimeout 120s`) so Neon suspends compute after ~5 min — a real cost lever. A timer-polled queue worker holds a connection forever and **kills that**. So the worker blocks on an in-process enqueue signal and only touches the DB during upload-triggered bursts (when it's already awake): drain-to-empty on wake, then block; recovery is **reap-at-boot + reap-on-wake** (a crash is always followed by a Fly restart → boot reap; a hang is bounded by `RESIZE-TIMEOUT-MS`), so no periodic reaper poll is needed. Steady-state idle = zero resize-related DB activity → the DB suspends exactly as before. This is *why* the worker must be in-process (Decision 2): the signal is an in-memory hand-off; a separate process could only poll or `LISTEN`, both of which break scale-to-zero. Correctness never depends on the signal arriving — only latency does (a lost signal is caught by the next wake / boot reap).

## Self-Review

- **Spec coverage:** in-process resize replacing the Lambda (Tasks 3–5) ✅; Postgres queue mirroring the sibling incl. the nullable-UNIQUE idempotency (Tasks 1–2, Decision 3) ✅; Transactional Outbox ordering + post-commit signal (Task 5, Decision 4) ✅; bounded concurrency as a value + subsampled decode + pixel guard (Tasks 3–4, Decisions 6, Constraints) ✅; timeout + crash-vs-poison attempts (Task 4, Decision 7) ✅; **event-driven worker preserves scale-to-zero — no idle polling, reap-on-boot/wake (Constraints, Tasks 4/6, Decision 9)** ✅; monitoring surface (Task 4/7, Decision 8) ✅; pure backoff/time (Task 3, Decision 5) ✅; in-process-only worker (scale-to-zero-forced, not just YAGNI), separate process cut (Task 6, Decision 2) ✅; config/docs/AWS retirement + acceptance gate (Task 7) ✅; queue↔Semaphore(1) reversal documented, now more attractive under scale-to-zero (Decision 1) ✅.
- **Placeholder scan:** DDL, pure tests, claim SQL shape, and the outbox ordering are concrete; `<photo-id>` etc. are runtime values; numeric knobs (`MAX-SOURCE-PIXELS`, `RESIZE-TIMEOUT-MS`, lease default) have stated defaults.
- **Type consistency:** `RENDITIONS` dims = `albums/IMAGE-VERSIONS` = the Go resizer; job columns mirror `workflow_jobs` (+ `active_photo_id`); `next-available-at`/`resize-job!`/`drain-once!`/`run-worker!`/`enqueue-job!` names consistent across Tasks 2–6; `retry-job!` takes a caller-computed `available_at` (Task 2) fed by `next-available-at` (Task 4).
