# Object Storage Model Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the per-tenant-bucket media storage model with a single per-environment object store whose keys encode only the object's intrinsic identity, so photos are portable across environments (fixing ephemeral photo loading) and no stored value ever needs to move when a tenant/hostname changes.

**Architecture:** Object storage is a flat key→blob map with no real hierarchy and no cheap rename. Therefore keys must be a pure function of **intrinsic, immutable identity** (`media/<photo-id>/<category>.<ext>`), and everything mutable — which tenant owns it, which environment serves it, which bucket it lives in — lives in the Postgres index or in per-environment config, never in the key. Location becomes `f(intrinsic-id, env-config)`: the environment supplies the bucket, the object supplies the key. The store is append-only; reclamation and index/store reconciliation are periodic, offline, gated, and reversible.

**Tech Stack:** Clojure, next.jdbc + HoneySQL, Migratus migrations, `cognitect.aws` S3 client (`persistence/filesystem/s3_impl.clj`), Kaocha + matcher-combinators tests with embedded-h2 / embedded-postgres, Fly.io + Neon (ephemeral env tooling under `scripts/ephemeral/`).

> **Revision 2026-07-19 (post-review).** Updated after a three-lens design review (values/simplicity, operability, evolutionary-migration). Net change: every safety guarantee this plan was proudest of is now enforced by a test or a type instead of by prose — see new Principle §8. Concretely: the resizer's URL-only contract and the Phase-1 inert-build guard are now load-bearing CI tests (Task 2); `content_hash` earns a *present* reader in reconciliation instead of being a speculative "open door" (§6–§7, Task 8); the reconciliation set-math moves out of bash into Clojure (Task 8); the orphan-bucket reaper becomes a real runnable task, not a recommendation — run manually for now, auto-scheduling deferred (Task 3b); and Phase 2 states the *real* reason it forgoes expand/contract — downtime is acceptable for an owner-scheduled personal CMS — and owns the code↔schema rollback coupling that choice creates (Phase 2 intro, Decision 4).

## Global Constraints

- **Migrations:** numbered `resources/migrations/YYYYMMDDHHMMSS-slug.up.sql` / `.down.sql`, statements separated by `--;;`. New columns use `ADD COLUMN IF NOT EXISTS` (idempotent under ephemeral retries). Never change schema without a migration pair.
- **Tenancy:** tenant-scoped tables carry `hostname VARCHAR NOT NULL`; tenancy is enforced by the `hostname`-scoped query + authorization layer, never by the storage key or bucket.
- **Naming:** SQL is `snake_case`, Clojure is `kebab-case` (auto-converted via camel-snake-kebab). `!` suffix on side-effecting fns.
- **3-layer separation:** no persistence calls from `http_api/`; no HTTP in `api/`. S3 access stays in `persistence/filesystem/`.
- **Every change ships with automated tests** (embedded-h2 default; embedded-postgres where behavior diverges).
- **Keep in sync in the same change:** `docs/operations.md` (any deploy/env/bucket change) and `Taskfile.yml` ↔ `bin/`/`scripts/` interface.
- **Legacy CMS caution:** touch `articles`/`albums`/`portfolio` only where this plan explicitly says to.

---

## Rationale & Principles

This section is the *why*. Every task below is downstream of it; if a task seems to conflict with these principles, the task is wrong.

### 1. We are using object storage on purpose

Photos are large, write-once/read-many blobs served over HTTP to the public web. That is exactly what object stores (S3 and S3-compatible services) are built for: effectively unbounded capacity with no provisioning, ~11-nines durability via internal replication, pay-per-use pricing, and stateless `GET`-by-key access from anywhere, fronted by a CDN. We are **not** using a filesystem/NAS (EFS/Filestore/Azure Files) — those add POSIX semantics we don't need at 10×+ the cost and a mount-based access model unsuited to web serving.

### 2. Object storage has no true hierarchy

An object store is a **flat map from key (a string) to a blob**. "Folders" are an illusion produced by `/` characters in keys plus delimiter-based listing — there is no directory object. Consequences that drive this design:

- **There is no atomic rename or move.** A "rename" is `CopyObject` + `DeleteObject`, per object, `O(n)`, non-atomic. Renaming a "folder" of a million objects copies and deletes a million times. A filesystem renames a directory in `O(1)` by re-pointing one inode entry; an object store cannot.
- **Therefore a key is expensive to change after the fact.** Whatever you encode into a key, you are committing to physically rewriting every affected object if that fact ever changes.

### 3. Keys must encode only intrinsic, immutable identity — never anything that drifts

Because keys are expensive to change, a key must contain only facts that are **intrinsic to the object and never change**:

- ✅ The photo's UUID and rendition category: `media/<photo-id>/<category>.<ext>`. A `photo-id` is assigned once and is permanent.
- ❌ **Tenant name / tenant id / hostname.** These are administrative and *mutable*: a site rebrands (`andrewslai.com` → `andrew.com`), tenants merge or split, content is re-homed. Putting them in the key means every such change becomes a copy-and-delete migration of every object.
- ❌ **Environment / bucket / storage driver.** These are deployment facts, not object facts. They belong in per-environment config, not baked into stored data.

**Corollary:** `storage_root` (today set to the tenant hostname = the bucket name) and `storage_driver` are exactly this anti-pattern — mutable/deployment facts stored per row. They are also already *ignored* on read (the serving adapter is chosen by the resolved tenant, not by these columns), so they are dead, misleading data. They are removed by this plan.

### 4. Everything mutable lives in the index or in config

- **Tenancy, ownership, liveness, authorization → the Postgres index.** The `hostname` column scopes tenancy; the audience/authorization layer governs access. This is the *same* bargain we already accept for metadata (all tenants in one database, isolated by `hostname`); we extend it to bytes (all tenants in one bucket, addressed by intrinsic key, never listed per-tenant).
- **Bucket / environment → config.** `location = f(intrinsic-id, env-config)`. The object supplies the key; the environment supplies the bucket via `KALEIDOSCOPE_MEDIA_BUCKET`.

### 5. Why this fixes ephemeral environments

Today an ephemeral env cannot use the real per-tenant buckets (isolation), so it invents a parallel prefix scheme and must seed/copy assets — and still 404s because its branched database points at objects that were never copied in. With intrinsic, tenant-agnostic, environment-agnostic keys, **the same key resolves in every environment**. An ephemeral env writes to its **own disposable per-env bucket** (`kal-eph-<slug>-media`, created on `up` / deleted on `down`) and **reads through to the production media bucket, read-only** — the branched DB's `media/<uuid>/…` keys resolve against the env's own bucket for its uploads, else the immutable prod corpus. **Zero copy, zero seed, always current.** No prefix appears in any key (the bucket *is* the namespace), and teardown is a single whole-bucket delete — no per-env leak to reconcile. Isolation is by construction: the writer is a bucket the env alone holds creds for; reads of immutable prod objects are safe to share (read-only credential). See Decision 1 for the tradeoffs (bucket lifecycle + the prod read grant).

### 6. Operational properties we accept and how we manage them

- **Append-only.** Deleting a photo drops the row and leaves the blob (an "orphan"). This keeps reclamation off the write path (no refcount races, no delete coupled to app writes). The leak is proportional to deletion/churn rate — negligible for this workload — and lifecycle tiering bounds even orphaned bytes to cold-tier pricing.
- **Reclamation & reconciliation are one offline pipeline.** Periodically: `S3 Inventory` manifest → diff against the set of keys derivable from live rows → `orphans = stored − referenced` (quarantine, then lifecycle-expire) and `dangling = referenced − stored` (alert: possible data loss). Gated (refuse if the referenced set shrank suspiciously or the index is unhealthy), reversible (quarantine prefix + bucket versioning), and **never run against a corrupted index** — restore the index from PITR first.
- **The index is the crown jewel.** It is the sole source of truth for liveness/ownership/auth and must have point-in-time recovery. The store is authoritative for bytes and must have versioning. Neither reconstructs the other, so both are protected.
- **A `content_hash` checksum column** (Phase 3, not used as the key) is populated **for new uploads only** — the existing corpus is *not* backfilled. It is **not speculative inventory: it earns its place by having a present reader.** Reconciliation (Task 8) diffs by *key* (independent of hashes) *and*, for every row that carries a `content_hash`, re-heads the object and verifies the stored bytes match — that is the integrity check the column exists to serve, active from the first post-Phase-3 upload. We add the column *when reconciliation starts consuming it* (both land in Phase 3), not before; a column nothing reads would not ship. Content-addressing (hash-*keyed* blobs) remains explicitly out of scope (§7) — this is a checksum we verify, not an identity we address by.

### 7. What we are explicitly NOT doing (YAGNI)

- **Not** content-addressing (hash-keyed blobs). Intrinsic UUID keys already give portability and dedup-free simplicity; content-addressing's extra powers (dedup, integrity-as-identity) aren't current needs. The `content_hash` column is a *checksum with a present job* — reconciliation verifies bytes against it (§6, Task 8) — **not** a door held open for a future we've decided not to build. If content-addressing ever becomes a real need, the hashes are already there; but the column ships to be *read now*, not to hedge.
- **Not** building an online/refcounted garbage collector.
- **Not** re-architecting article-embedded-image storage or its access control here — that is a **separate plan** (`plans/…-article-embedded-asset-acl/`). This plan is media/object storage only.
- **Not** changing how the SPA shell / `/static/*` site chrome is served — that content is genuinely per-tenant and per-env and stays on the existing `static-content-adapters` map.

### 8. Every safety guarantee is enforced by structure or CI — never by prose alone

This plan leans on several subtle correctness arguments (the inert Phase-1 build, the resizer following whatever bucket the URL names, `write-location` and `put-file` never drifting, an ephemeral env never writing prod). An argument that lives only in a paragraph is an argument a future contributor will silently break — the gallery goes blank *weeks* later, far from the edit that caused it. So the rule for this plan is: **if we are proud of a guarantee, it must fail loudly.** Prefer, in order:

1. **Make it unrepresentable in a type.** `ReadThroughFS`'s reader/writer asymmetry is the gold standard — a write *cannot* reach a non-writer store; there is no method-body discipline to forget. This is the model the rest of the plan is measured against.
2. **Assert it in CI so a violating edit is a red build**, not a production incident. The resizer's "message body is exactly `s3://bucket/key`, no attributes" contract, the `write-location`↔`put-file` equality, and the media-store-*absent* inert-build behavior are all pinned by tests (Task 2). Changing the notify shape must break the suite in the same commit, not the gallery next month.
3. **Only then, prose** — reserved for genuinely external contracts we cannot execute in-repo (e.g. the deployed Lambda's IAM), and even those get a documented end-to-end fitness check (Task 6).

When a task adds a clever invariant, the reviewer's question is: *what test turns red if someone violates this?* "None, but the comment explains it" is a rejected answer.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `resources/migrations/…-add-media-content-hash.up/down.sql` | Add `content_hash` checksum column to `photo_versions` | Create |
| `resources/migrations/…-drop-photo-storage-location.up/down.sql` | Remove `storage_root` / `storage_driver` after cutover | Create |
| `src/kaleidoscope/persistence/filesystem/read_through.clj` | `ReadThroughFS` overlay: ordered reader chain, single writer | Create |
| `src/kaleidoscope/init/env.clj` | Register the single per-env media store; route photo reads to it | Modify |
| `src/kaleidoscope/api/albums.clj` | `make-image-version` / `new-image`: derive key, drop `storage_root`/`storage_driver`, set `content_hash` | Modify |
| `src/kaleidoscope/http_api/photo.clj` | Serve photos via the media store | Modify |
| `scripts/ephemeral/{lib.sh,up,down,deploy-app}` | Per-env media bucket lifecycle (create/empty+delete) + `KALEIDOSCOPE_MEDIA_BUCKET`/fallback; retire photo seeding | Modify |
| `scripts/ephemeral/reap-orphan-buckets` | Delete `kal-eph-*-media` buckets with no live Fly app (on-demand failed-teardown reaper; run manually, scheduling deferred) | Create |
| `scripts/media/consolidate-buckets` | One-time server-side sync of per-tenant media into the single bucket (bash glue over `aws s3 sync`) | Create |
| `src/kaleidoscope/tasks/reconcile.clj` + `scripts/media/reconcile` | Offline inventory-diff reclamation/reconciliation + checksum verification. **Set-math and safety gates in Clojure** (typed, tested); the shell script is a thin launcher only | Create |
| `test/kaleidoscope/http_api/photo_resize_contract_test.clj` | Pins the resizer notify contract (message body = `s3://bucket/key`, no attributes) — the CI guard for §8 | Create |
| `docs/operations.md` | Document the media bucket, env vars, consolidation, reconciliation, lifecycle rules | Modify |
| `Taskfile.yml` | Expose `media:consolidate`, `media:reconcile`, `media:verify-resize`, `ephemeral:reap` | Modify |

The work is organized in **three independently shippable phases**. Each phase produces working, tested software on its own.

- **Phase 1 — Ephemeral read-through (no prod migration).** Smallest change that fixes the reported 404 and validates the model.
- **Phase 2 — Prod consolidation + cutover.** One media bucket in prod; remove the drift columns.
- **Phase 3 — Hardening.** Checksum integrity, reconciliation job, lifecycle rules.

---

## Phase 1 — Ephemeral read-through (fix the 404, validate the model)

> **Linchpin property — the whole downtime scheme depends on this.** Phase 1's code and the `DROP COLUMN` migration land on `master`, so **prod adopts them on its very next deploy — before the Phase-2 window**, not when we choose. That is safe *only because* the Phase-1 build is **inert in prod while `KALEIDOSCOPE_MEDIA_BUCKET` is unset**: media store absent → uploads use the per-tenant adapter → `write-location` yields the tenant bucket + key → the resize `:message` URL is byte-identical to today's → the old resizer keeps working; the dropped columns are simply never written. "Prod untouched" means *behavior* untouched, not *code* untouched. Two guards keep this property true, and **both are executable CI tests, not paragraphs** (§8) — a commit that breaks either goes red before it can blank a gallery: **(a)** the media-store-absent regression test (Task 2, Step 1) pins the inert behavior — media store absent ⇒ per-tenant adapter ⇒ byte-identical resize URL; **(b)** the resizer notify-contract test (Task 2, `photo_resize_contract_test`) pins the notify `:message` to exactly `s3://<bucket>/<key>` with no message-attributes. The resizer parses the URL from the message *body* and reads nothing else (see the resizer analysis), so a hard cut isn't a risk to *weigh* — it is a *red build*. If someone ever "cleans up" the message into structured attributes, guard (b) fails in the same commit, not in production three weeks later. The maintenance window (Phase 2) only gates consolidation, the `MEDIA_BUCKET` flip, and the resizer — never the code.

### Task 1: Add a `ReadThroughFS` overlay combinator (explicit reader/writer separation)

**Files:**
- Create: `src/kaleidoscope/persistence/filesystem/read_through.clj`
- Test: `test/kaleidoscope/persistence/filesystem/read_through_test.clj`

**Interfaces:**
- Produces: `(->ReadThroughFS writer readers)` — `writer` is one `DistributedFileSystem`; `readers` is an ordered seq of `DistributedFileSystem`. `get-file` returns the first reader whose result exists (falling through the chain in order), else `fs/does-not-exist-response`; `put-file` and `ls` delegate to `writer` **only**. The base `S3` record is **unchanged**. The reader/writer asymmetry is structural — a write can never reach a non-writer store (an ephemeral env can never mutate the shared read-only media bucket), and that guarantee lives in the type, not in method-body discipline.

- [ ] **Step 1: Write the failing test** (compose in-memory stores — the combinator is backend-agnostic, so no S3 or `aws/invoke` mocking is needed). Build the `mem` map values in the shape the in-memory impl stores them — see `memory/example-fs` for the canonical shape.

```clojure
(ns kaleidoscope.persistence.filesystem.read-through-test
  (:require [clojure.test :refer [deftest is]]
            [kaleidoscope.persistence.filesystem :as fs]
            [kaleidoscope.persistence.filesystem.in-memory-impl :as memory]
            [kaleidoscope.persistence.filesystem.read-through :as rt]))

(defn mem [contents] (memory/make-mem-fs {:store (atom contents)}))   ;; contents shaped like memory/example-fs
(defn content-str [o] (slurp (fs/object-content o)))

(deftest reads-fall-through-to-later-readers
  (let [own    (mem {})
        shared (mem {"media/abc/raw.jpg" "IMG"})
        media  (rt/->ReadThroughFS own [own shared])]
    (is (= "IMG" (content-str (fs/get-file media "media/abc/raw.jpg" {}))))))

(deftest reads-prefer-the-first-reader
  (let [own    (mem {"media/abc/raw.jpg" "OWN"})
        shared (mem {"media/abc/raw.jpg" "SHARED"})
        media  (rt/->ReadThroughFS own [own shared])]
    (is (= "OWN" (content-str (fs/get-file media "media/abc/raw.jpg" {}))))))

(deftest missing-everywhere-returns-does-not-exist
  (let [media (rt/->ReadThroughFS (mem {}) [(mem {}) (mem {})])]
    (is (fs/does-not-exist? (fs/get-file media "media/nope/raw.jpg" {})))))

(deftest writes-only-touch-the-writer                    ;; the isolation guarantee, by construction
  (let [own    (mem {})
        shared (mem {})
        media  (rt/->ReadThroughFS own [own shared])]
    (fs/put-file media "media/xyz/raw.jpg" (java.io.ByteArrayInputStream. (.getBytes "NEW")) {})
    (is (not (fs/does-not-exist? (fs/get-file own    "media/xyz/raw.jpg" {}))))
    (is      (fs/does-not-exist? (fs/get-file shared "media/xyz/raw.jpg" {})))))
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.persistence.filesystem.read-through-test`
Expected: FAIL (namespace / record does not exist).

- [ ] **Step 3: Implement the combinator**

```clojure
(ns kaleidoscope.persistence.filesystem.read-through
  "Overlay filesystem: read through an ordered chain of stores, write to exactly
  one. The reader/writer asymmetry is structural — a write can never reach a
  non-writer store (an ephemeral env can never mutate the shared media bucket)."
  (:require [kaleidoscope.persistence.filesystem :as fs]))

(defrecord ReadThroughFS [writer readers]
  fs/DistributedFileSystem
  (get-file [_ path options]
    (or (some (fn [store]
                (let [result (fs/get-file store path options)]
                  (when-not (fs/does-not-exist? result) result)))   ;; not-modified (304) counts as found
              readers)
        fs/does-not-exist-response))
  (put-file [_ path input-stream metadata]
    (fs/put-file writer path input-stream metadata))
  (ls [_ path options]
    (fs/ls writer path options)))
```

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.persistence.filesystem.read-through-test`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/persistence/filesystem/read_through.clj test/kaleidoscope/persistence/filesystem/read_through_test.clj
git commit -m "feat(fs): add ReadThroughFS overlay combinator (explicit reader/writer split)

- get-file falls through an ordered reader chain; put-file/ls target one writer
- write isolation is structural: a write can never reach a non-writer store
- base S3 record unchanged; overlay composes any DistributedFileSystem backends"
```

### Task 2: Register a single per-env media store selected by config

**Files:**
- Modify: `src/kaleidoscope/init/env.clj` (the `"s3"` launcher in `kaleidoscope-static-content-adapter-boot-instructions`, ~line 230)
- Modify: `src/kaleidoscope/http_api/photo.clj` (serve + upload use the media store)
- Test: `test/kaleidoscope/init/env_test.clj`

**Interfaces:**
- Consumes: `ReadThroughFS` (Task 1) and `make-s3` (unchanged base record).
- Produces: when `KALEIDOSCOPE_MEDIA_BUCKET` is set, the static-content adapter map contains a store under key `tenant/media-store` (new const, value `"media"`). With no fallback it is a plain `S3` (`:bucket <MEDIA_BUCKET>`); with `KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET` set it is a `ReadThroughFS` whose `:writer` is the own-bucket store (`kal-eph-<slug>-media`) and whose `:readers` are `[own prod]` (read own writes first, then prod read-only). **No prefix anywhere** — the per-env bucket is the namespace, and every key is bare `media/<uuid>/…` in both readers. Photo read/upload resolve this store directly, independent of the resolved tenant's `:asset-store`.

- [ ] **Step 1: Write the failing test**

```clojure
(deftest media-store-is-plain-store-when-no-fallback
  (let [adapters ((get-in kaleidoscope-static-content-adapter-boot-instructions
                          [:launchers "s3"])
                  {"KALEIDOSCOPE_MEDIA_BUCKET" "kal-media"})]
    (is (= "kal-media" (:bucket (get adapters tenant/media-store))))))

(deftest media-store-is-read-through-overlay-when-fallback-set
  (let [adapters ((get-in kaleidoscope-static-content-adapter-boot-instructions
                          [:launchers "s3"])
                  {"KALEIDOSCOPE_MEDIA_BUCKET"          "kal-media"
                   "KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET" "kal-media-prod"})
        media    (get adapters tenant/media-store)]
    (is (= "kal-media" (:bucket (:writer media))))                              ;; writes → own bucket only
    (is (= ["kal-media" "kal-media-prod"] (map :bucket (:readers media))))))    ;; reads → own then prod
```

Then add the **load-bearing regression test** — the media-store-*absent* branch (= prod with `MEDIA_BUCKET` unset). This is what guarantees merging Phase 1 leaves prod's behavior unchanged (see the Phase-1 linchpin note), so it is not optional. Drive the serve + upload handlers with **no** media store registered and assert the old behavior exactly: serve falls through to `hu/get-resource` against the tenant `asset-store` adapter, and `process-photo-upload!` writes through that same per-tenant adapter:

```clojure
(deftest serve-and-upload-unchanged-when-media-store-absent
  (let [adapters {"andrewslai.com" (mem-fs-with {"media/abc/raw.jpg" "IMG"})}   ;; NO tenant/media-store key
        req      (-> (mock/request :get "/v2/photos/abc/raw.jpg")
                     (assoc :tenant     {:hostname "andrewslai.com" :asset-store "andrewslai.com"}
                            :components {:static-content-adapters adapters :database db}))]
    ;; serve resolves via :asset-store, not the (absent) media store — behavior identical to before
    (is (= 200 (:status (serve-photo req))))
    ;; upload writes through the per-tenant asset-store adapter — object lands where it always did
    (let [{:keys [photo-id]} (process-photo-upload! req {:filename "x.jpg" :tempfile (tmp "NEW")})]
      (is (not (fs/does-not-exist?
                 (fs/get-file (get adapters "andrewslai.com") (format "media/%s/raw.jpg" photo-id) {})))))))
```

Finally, **pin `write-location` to the exact `(bucket, key)` `put-file` writes** — this is the guard against the resizer's one residual risk (the raw's location being derived twice and silently drifting; a reintroduced `:prefix` or a renamed folder must fail *here*, not blank the gallery weeks later):

```clojure
(deftest write-location-matches-the-put-target
  ;; put-file writes to (:bucket, (prefixed-key :prefix raw-key)); the resizer URL is built from
  ;; write-location. They MUST be equal or the resizer 404s and renditions vanish silently.
  (are [store expected] (= expected (fs/write-location store "media/abc/raw.jpg"))
    (s3/make-s3 {:bucket "kal-media-prod"})
    {:bucket "kal-media-prod" :key "media/abc/raw.jpg"}                 ;; plain, no prefix

    (s3/make-s3 {:bucket "kal-ephemeral" :prefix "eph-x/"})
    {:bucket "kal-ephemeral" :key "eph-x/media/abc/raw.jpg"}            ;; prefix folded into the key

    (rt/->ReadThroughFS (s3/make-s3 {:bucket "kal-eph-x-media"}) [])
    {:bucket "kal-eph-x-media" :key "media/abc/raw.jpg"}))             ;; delegates to the writer
```

Finally, add the **resizer notify-contract test** (`test/kaleidoscope/http_api/photo_resize_contract_test.clj`) — this is guard (b) of the Phase-1 linchpin promoted from prose to CI (§8). It captures what the upload handler hands the notifier and asserts the *exact* shape the deployed Lambda parses: the body is `s3://<write-location-bucket>/<write-location-key>` and there are **no message-attributes** (the resizer ignores them; reintroducing them is dead complexity that reads as meaningful). A change to the message shape must turn this red in the same commit — the gallery cannot silently go blank weeks later.

```clojure
(deftest resize-notify-message-is-exactly-the-write-location-url
  ;; The deployed resizer reads ONLY the s3://bucket/key in the message body.
  ;; This test IS the contract — break the shape here, not in production.
  (let [store   (s3/make-s3 {:bucket "kal-media-prod"})
        sent    (atom nil)
        notify! (fn [& {:keys [message message-attributes]}]
                  (reset! sent {:message message :attrs message-attributes}))]
    (with-redefs [photo/notify-image-resizer! notify!]
      (process-photo-upload! (req-with-store store) {:filename "x.jpg" :tempfile (tmp "IMG")}))
    (let [{:keys [bucket key]} (fs/write-location store (raw-key-of @sent))]
      (is (= (format "s3://%s/%s" bucket key) (:message @sent)))   ;; body = write-location URL, verbatim
      (is (nil? (:attrs @sent))))))                                ;; NO attributes — resizer ignores them
```

- [ ] **Step 2: Run to verify it fails**

Run: `./bin/test --focus kaleidoscope.init.env-test kaleidoscope.http-api.photo-resize-contract-test`
Expected: FAIL (`tenant/media-store` undefined / not registered; notify contract test unimplemented).

- [ ] **Step 3: Implement**

- Add `(def media-store "media")` to `src/kaleidoscope/http_api/tenant.clj` (next to `ephemeral-asset-store`).
- Require `[kaleidoscope.persistence.filesystem.read-through :as rt]` in `init/env.clj`.
- In the `"s3"` launcher, `cond->` in the media store when `KALEIDOSCOPE_MEDIA_BUCKET` is present — a plain store when there is no fallback, else a `ReadThroughFS` (writer = own bucket; readers = own then fallback):
  ```clojure
  (get env "KALEIDOSCOPE_MEDIA_BUCKET")
  (assoc tenant/media-store
         (let [own  (s3-storage/make-s3 {:bucket (get env "KALEIDOSCOPE_MEDIA_BUCKET")})   ;; per-env bucket, no prefix
               prod (when-let [b (get env "KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET")]
                      (s3-storage/make-s3 {:bucket b}))]
           (if prod
             (rt/->ReadThroughFS own [own prod])
             own)))
  ```
- **Read path** — in `http_api/photo.clj`, the `/:photo-id/:filename` serve handler resolves the media store explicitly instead of via `hu/asset-store`: fetch `(get (:static-content-adapters components) tenant/media-store)` and call `fs/get` with the row's `path`. Fall back to the existing `hu/get-resource` path only when `media-store` is absent (so non-media envs are unaffected).
- **Write path + `new-image` — the full code change** (validated in ephemeral now; deployed to prod in the Phase-2 window). Because there is no rolling-deploy coexistence (ephemeral is provisioned fresh; prod cuts over in a maintenance window), we make all of it at once — no phased "still set the columns for now":
  - **Adapter selection** in `process-photo-upload!`: media store when registered, else the tenant `asset-store` adapter — `(get static-content-adapters tenant/media-store (get static-content-adapters (hu/asset-store req)))`. Ephemeral uploads land in the env's own bucket; prod (no `KALEIDOSCOPE_MEDIA_BUCKET` yet) writes the per-tenant bucket as before.
  - **`make-image-version`/`new-image` build the key from the `MEDIA-FOLDER` constant directly** (`media/<id>/<cat>.<ext>`) instead of reading `:photos-folder` off the adapter — this deletes the assoc indirection and the whole `:photos-folder` finding.
  - **Stop setting `:storage-root`/`:storage-driver`** on the version rows.
  - **Resize notify — compute the raw's location *once* and reuse it, so "where the raw is" can't be derived twice and drift** (see the resizer analysis: the Lambda parses the `s3://bucket/key` URL from the message *body* and writes renditions into that same bucket; it ignores message attributes and its IAM already covers `s3:::*`, so **the resizer needs no code or IAM change** — the entire contract is "name the right bucket + key in the URL"):
    ```clojure
    (let [raw-key (format "%s/%s/raw.%s" MEDIA-FOLDER photo-id extension)
          {:keys [bucket key]} (fs/write-location static-content-adapter raw-key)]  ;; bucket + prefix-adjusted key
      (fs/put-file static-content-adapter raw-key (u/->file-input-stream tempfile) meta)
      (notify-image-resizer! :subject "image-resize-requested"
                             :message (format "s3://%s/%s" bucket key)))              ;; body = URL; NO attributes
    ```
    - `raw-key` is computed once and used for **both** the write and the notify — the key can't diverge.
    - `write-location` (new, `persistence/filesystem.clj` + `read_through.clj`) returns the store's real write target as `{:bucket … :key …}`: for `S3`, `{:bucket (:storage-root store), :key (prefixed-key (:prefix store) raw-key)}`; for `ReadThroughFS`, `(write-location writer raw-key)`. This folds in any `:prefix` automatically, so the URL always points at exactly where `put-file` wrote — even if a prefix is ever reintroduced. (Supersedes the earlier bucket-only `write-root`.)
    - **Drop the `:message-attributes`** — they were dead (the resizer reads only the URL body). Keep `:message` as the `s3://…` URL; the resizer cannot be hard-cut off it.
    - Backward-compatible: with `MEDIA_BUCKET` unset the write store is the per-tenant adapter, so `bucket` = `<hostname>` and the URL is byte-identical to today's. Inert in ephemeral (`notifier = none`), but `write-location` is still evaluated there, so it must handle `ReadThroughFS`.
  - **Ship the drop-columns migration in this same build:** `resources/migrations/…-drop-photo-storage-location.up/down.sql` — `DROP COLUMN storage_root; --;; DROP COLUMN storage_driver;`. Down is reversible/lossless (re-add + backfill `storage_root = hostname`, `storage_driver = 's3'`). Applied to each ephemeral branch at `provision-db`; applied to prod when this build deploys in the Phase-2 window. **Co-shipping the migration with the code that stops writing the columns is what makes the drop safe without any expand/contract nullable step** — no build ever runs where the schema and the code disagree.

- [ ] **Step 4: Run to verify it passes**

Run: `./bin/test --focus kaleidoscope.init.env-test kaleidoscope.http-api.photo-resize-contract-test`
Expected: PASS (media store registered; both linchpin guards green).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/init/env.clj src/kaleidoscope/http_api/tenant.clj src/kaleidoscope/http_api/photo.clj test/kaleidoscope/init/env_test.clj test/kaleidoscope/http_api/photo_resize_contract_test.clj
git commit -m "feat(media): register a single per-env media store selected by KALEIDOSCOPE_MEDIA_BUCKET

- photos serve AND upload via the media store (bucket from config), not the per-tenant asset map
- uploads route to the media store when registered, so ephemeral uploads round-trip
- optional KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET enables read-through to prod media (read-only)
- pin the resizer notify contract (message body = s3://bucket/key, no attributes) in CI
- pin write-location == put-file target and the media-store-absent inert behavior (Phase-1 guards)"
```

### Task 3: Wire ephemeral envs — per-env media bucket + read-through to prod

**Files:**
- Modify: `scripts/ephemeral/lib.sh` (derived names + bucket lifecycle helpers)
- Modify: `scripts/ephemeral/up` (create the per-env bucket), `scripts/ephemeral/down` (empty + delete it)
- Modify: `scripts/ephemeral/deploy-app` (secrets block, ~line 100)
- Modify: `docs/operations.md`

**Interfaces:**
- Produces: each env has a dedicated bucket `kal-eph-<slug>-media` created at `up` and deleted at `down`. Deploys set `KALEIDOSCOPE_MEDIA_BUCKET="$(media_bucket "$SLUG")"` and `KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET="$PROD_MEDIA_BUCKET"` (the pinned tenant's prod bucket read-only, e.g. `andrewslai.com`; `kal-media-prod` after Phase 2). **No `KALEIDOSCOPE_MEDIA_PREFIX`** — the bucket is the namespace. Task 2's launcher composes these into `ReadThroughFS(writer = per-env-bucket, readers = [per-env-bucket, prod])`.

- [ ] **Step 1:** In `lib.sh`, add `media_bucket() { printf 'kal-eph-%s-media' "$1"; }` and `PROD_MEDIA_BUCKET="${PROD_MEDIA_BUCKET:-$TENANT}"` (pre-Phase-2 the read-through source is the pinned tenant's own bucket; set `PROD_MEDIA_BUCKET=kal-media-prod` after consolidation).
- [ ] **Step 2 (lifecycle):** In `scripts/ephemeral/up`, create the bucket idempotently: `aws s3api create-bucket --bucket "$(media_bucket "$SLUG")" --region "$AWS_REGION" …` (no-op if it exists). In `scripts/ephemeral/down`, **empty then delete**: `aws s3 rm "s3://$(media_bucket "$SLUG")" --recursive; aws s3api delete-bucket --bucket "$(media_bucket "$SLUG")"`. Keep `docs/operations.md` and `Taskfile.yml` ↔ `bin/` in sync.
- [ ] **Step 3:** In `deploy-app` `fly secrets set`, add:
  ```
  KALEIDOSCOPE_MEDIA_BUCKET="$(media_bucket "$SLUG")" \
  KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET="$PROD_MEDIA_BUCKET" \
  ```
  Remove the photo half of `KALEIDOSCOPE_TENANT_ASSET_*` from the media path (leave it only for `/static/*` chrome, or drop if chrome is handled separately).
- [ ] **Step 4 (IAM):** Scope the ephemeral IAM user to (a) `CreateBucket`/`DeleteBucket` + object read-write on the `kal-eph-*-media` name pattern, and (b) **read-only** on the prod media bucket(s) — the Decision 1 least-privilege tradeoff. Document both grants in `docs/operations.md`. Note pre-Phase-2 the read grant spans every tenant bucket the shared ephemeral user might read; it narrows to `kal-media-prod` after consolidation.
- [ ] **Step 5: Verify against a live ephemeral env — read-through AND isolated upload round-trip:**

```bash
# after `task ephemeral:up NAME=<slug>`
curl -s -o /dev/null -w "existing photo (read-through to prod): HTTP %{http_code}\n" \
  "https://kal-eph-<slug>.fly.dev/v2/photos/<known-photo-id>/raw.JPG"        # Expect 200 (was 404)
curl -s -F "file=@sample.jpg" "https://kal-eph-<slug>.fly.dev/v2/photos"     # Expect 201/200; note the photo-id
curl -s -o /dev/null -w "new upload: HTTP %{http_code}\n" \
  "https://kal-eph-<slug>.fly.dev/v2/photos/<new-photo-id>/raw.jpg"          # Expect 200
# isolation: object is in the env's own bucket, NOT in prod
aws s3 ls "s3://$(media_bucket "<slug>")/media/<new-photo-id>/"             # present
aws s3 ls "s3://$PROD_MEDIA_BUCKET/media/<new-photo-id>/"                    # absent
# teardown removes it entirely
task ephemeral:down NAME=<slug>; aws s3 ls "s3://$(media_bucket "<slug>")"  # bucket gone
```

- [ ] **Step 6:** Update `docs/operations.md` (per-env media bucket lifecycle, env vars, IAM grants) and retire the `seed-tenant-assets` photo invocation from `scripts/ephemeral/up`. Commit.

```bash
git add scripts/ephemeral/deploy-app scripts/ephemeral/lib.sh scripts/ephemeral/up scripts/ephemeral/down docs/operations.md
git commit -m "feat(ephemeral): per-env media bucket + read-through to prod

- each env gets kal-eph-<slug>-media (created on up, emptied+deleted on down); no prefix in keys
- reads fall through to prod (read-only); uploads land in the per-env bucket, disposed at teardown
- fixes ephemeral photo 404s with zero copy/seed; retires photo asset seeding; docs/operations.md updated"
```

### Task 3b: Orphan-bucket reaper (a real runnable task — scheduling deferred)

`down` empties-then-deletes the per-env bucket, but a *failed* teardown (crashed `down`, killed CI job, AWS hiccup) leaves a `kal-eph-<slug>-media` bucket behind — each one holding a live read credential on prod media and eating into the ~100/account bucket ceiling (Decision 1). "An orphan reaper should exist" is exactly the kind of prose §8 forbids: promote it to a task that actually runs on demand. **We are not automating the schedule yet** — build the tool, run it by hand when needed, and add a cron/Fly-scheduled trigger later once we've seen how often orphans actually appear (tracked as a follow-up).

**Files:** Create `scripts/ephemeral/reap-orphan-buckets`; modify `Taskfile.yml` (`ephemeral:reap`), `docs/operations.md`.

- [ ] **Step 1:** Write `scripts/ephemeral/reap-orphan-buckets` (bash, `set -euo pipefail`). List buckets matching `kal-eph-*-media`; for each, derive `<slug>` and check whether the Fly app `kal-eph-<slug>` exists (`fly status -a` / `fly apps list`). If **no live app**, empty-then-delete the bucket. Print every action; support `--dry-run` (default) vs `--apply` so a human sees the kill list before anything is deleted.
- [ ] **Step 2 (safety gate):** Refuse to reap a bucket younger than a grace period (e.g. 6h, via `CreationDate`) so an env mid-provision — app not yet created — is never swept. Never touch a bucket that doesn't match the `kal-eph-*-media` pattern; never touch `kal-media-prod` or any per-tenant bucket.
- [ ] **Step 3:** Add `ephemeral:reap` to `Taskfile.yml`; document the pattern, grace period, `--dry-run`/`--apply` contract, and **"run manually when orphans are suspected; scheduling is a deferred follow-up"** in `docs/operations.md`. Commit.

```bash
git add scripts/ephemeral/reap-orphan-buckets Taskfile.yml docs/operations.md
git commit -m "feat(ephemeral): on-demand reaper for orphaned kal-eph-*-media buckets

- reaps per-env buckets whose Fly app no longer exists (failed teardowns)
- grace period + pattern guard so mid-provision envs and prod buckets are never touched
- --dry-run default, --apply to delete; run manually (scheduling deferred); docs/operations.md updated"
```

**Phase 1 exit criteria:** in a fresh ephemeral env, existing photos load *and* new uploads round-trip with no seeding step; uploaded objects live only in the env's `kal-eph-<slug>-media` bucket (absent from prod) and vanish when the bucket is deleted at teardown; an on-demand reaper (Task 3b) exists so a failed teardown can be cleaned up before orphan buckets accumulate (auto-scheduling deferred); all existing tests pass — **including both Phase-1 CI guards: the media-store-absent regression test and the resizer notify-contract test**, which together are what make prod's *behavior* untouched even after prod deploys this build with `MEDIA_BUCKET` unset (see the Phase-1 linchpin note). Neither guard is prose; a commit that breaks either is a red build.

---

## Phase 2 — Prod cutover (one maintenance window)

The entire code change — media store, `new-image` (constant folder, no `storage_root`, `write-location` notify), the read/write routing, and the `DROP COLUMN` migration — already shipped and was validated in ephemeral in Phase 1. Prod may already be running the Phase-1 build (adopted on its last deploy) but is **inert** with `MEDIA_BUCKET` unset — old adapter, per-tenant bucket, notify URL unchanged, columns harmlessly dropped (see the Phase-1 linchpin note). Phase 2 is **operational only**: one scheduled maintenance window flips prod.

**Why a maintenance window instead of Parallel Change (expand/contract) — the honest reason.** The standard zero-downtime move here is expand/contract: release N stops *writing* the columns and soaks; release N+1 *drops* them — two independently-safe releases, no window. We are deliberately **not** doing that, and the reason is **not** that "co-shipping the migration makes expand/contract unnecessary." Co-shipping doesn't remove the need for parallel change; **accepting downtime does.** This is a single-owner personal CMS where an owner-scheduled maintenance window costs essentially nothing, and one atomic flip is far less machinery to build, reason about, and get right than a two-release expand/contract dance plus feature-flag bookkeeping. We choose the window because **the downtime is cheap here**, not because the migration technique made it free.

**The cost we accept for that choice (owning the coupling).** Because the `DROP COLUMN` migration rides in the *same* build as the code that stops writing the columns, code and schema are coupled: you cannot roll back the *code* without also reverting the *schema*, and vice-versa. The upside is real — no build ever runs where schema and code disagree, so there's no expand/contract window of divergence — but the trade is genuine loss of independent rollback granularity. We mitigate it, not eliminate it: the migration `down` is lossless (re-add + backfill `storage_root = hostname`, `storage_driver = 's3'`), rollback is scripted (Task 5 Rollback lever), and a prod DB snapshot is taken before the window regardless. If this app ever grew a real availability SLO, this is the first decision to revisit — switch to expand/contract and delete the window.

### Task 4: Consolidation script (built and dry-run before the window)

**Files:** Create `scripts/media/consolidate-buckets`; modify `Taskfile.yml`, `docs/operations.md`.

- [ ] **Step 1:** Write `scripts/media/consolidate-buckets` (bash, `set -euo pipefail`). For each tenant in `resources/tenants.json`: `aws s3 sync "s3://$tenant/media/" "s3://kal-media-prod/media/"` (same-region, server-side, no `--delete`; UUID keys → lossless merge). Runnable incrementally any time before the window; a final in-window run catches the delta.
- [ ] **Step 2:** Verification pass — for a sample of `photo_versions.path`, `aws s3api head-object --bucket kal-media-prod --key "$path"` must succeed. Fail loudly on any miss.
- [ ] **Step 3:** Add `media:consolidate` to `Taskfile.yml`; document in `docs/operations.md`; run an initial incremental sync. Commit.

```bash
git add scripts/media/consolidate-buckets Taskfile.yml docs/operations.md
git commit -m "feat(media): add per-tenant -> kal-media-prod consolidation script"
```

### Task 5: The maintenance-window runbook

No code changes here — the code shipped in Phase 1 (Task 2). This is the operator runbook for the scheduled window. Announce downtime, then:

- [ ] **Step 1 — quiesce:** stop the prod app (or set maintenance mode) so no photos are written mid-cutover.
- [ ] **Step 2 — final consolidation:** run `media:consolidate` once more so `kal-media-prod` holds every object written since the last incremental sync; re-run the head-object verification.
- [ ] **Step 3 — resizer: nothing to deploy (verify only).** The resizer is bucket-agnostic — it parses `s3://bucket/key` from the message body and writes renditions into that same bucket, and its IAM already grants `s3:::*` (see the resizer analysis). So it needs **no code or IAM change**. Verify only: (a) `new-image`'s URL is built from `write-location` (so it names `kal-media-prod` post-flip), and (b) the SNS topic → SQS subscription still delivers (unchanged). There is no "resizer cutover ordering" — it follows whatever bucket the URL names.
- [ ] **Step 4 — deploy the Phase-1 build to prod:** applies the `DROP COLUMN storage_root/storage_driver` migration (co-shipped with the code that stopped writing them → safe) and ships `new-image`'s media-store write + `write-location` `s3://…` notify.
- [ ] **Step 5 — flip:** set `KALEIDOSCOPE_MEDIA_BUCKET=kal-media-prod` in prod secrets; restart. Prod now serves and writes the single bucket, and notifies the resizer with `bucket=kal-media-prod`.
- [ ] **Step 6 — smoke test, then reopen:**
  ```bash
  curl -s -o /dev/null -w "existing: %{http_code}\n" "https://andrewslai.com/v2/photos/<known-id>/raw.JPG"    # 200
  curl -s -F "file=@sample.jpg" "https://andrewslai.com/v2/photos"                                            # 201; note id
  curl -s -o /dev/null -w "raw: %{http_code}\n"     "https://andrewslai.com/v2/photos/<id>/raw.jpg"           # 200
  curl -s -o /dev/null -w "gallery: %{http_code}\n" "https://andrewslai.com/v2/photos/<id>/gallery.jpg"       # 200 once the resizer runs
  ```
  The curls above are a quick eyeball; the **authoritative reopen gate is `task media:verify-resize` (Task 6)** — the automated upload⇒rendition fitness function. Reopen only when it exits green.
- [ ] **Rollback lever:** if the smoke test fails, unset `KALEIDOSCOPE_MEDIA_BUCKET` (prod reverts to the per-tenant bucket + old adapter; the notify `:message` URL is unchanged so the old resizer still works). If the schema must revert too, run the migration `down` (re-add + backfill `storage_root = hostname`, `storage_driver = 's3'` — derivable, lossless). Snapshot the prod DB before the window regardless.

> The `DROP COLUMN` migration (and its reversible `down`) lives in the Phase-1 build (Task 2), not here — applied to ephemeral branches at provision and to prod in Step 4 above. There is no separate expand/contract or drop task.

### Task 6: Automated resize round-trip fitness function (retire the manual curl as the only check)

The resizer is an *external, deployed* contract we can't unit-test in-repo — exactly the case §8 reserves for prose, and therefore exactly the case that earns a **documented, executable end-to-end fitness function** rather than the one-off `curl` in Task 5 Step 6. A manual smoke test run once during the window verifies the cutover moment; it does nothing to catch a *later* regression (someone changes the notify shape, the SNS→SQS subscription, or the resizer's IAM) that silently stops renditions from appearing. This task makes "upload ⇒ rendition appears" a repeatable, scheduled check.

**Files:** Create `scripts/media/verify-resize-roundtrip` (thin launcher) + `src/kaleidoscope/tasks/verify_resize.clj` (or a tagged integration test gated behind a live-env flag); modify `Taskfile.yml` (`media:verify-resize`), `docs/operations.md`.

- [ ] **Step 1:** Implement the round-trip against a target env: upload a fixture image → poll `GET /v2/photos/<id>/gallery.jpg` until 200 or a timeout → assert the rendition exists and is non-empty. Fail loudly (non-zero exit, clear message naming the broken hop) on timeout.
- [ ] **Step 2:** Add `media:verify-resize` to `Taskfile.yml`; run it as the *final* post-flip gate in the Task 5 runbook (replacing reliance on the eyeball curl) and on a schedule against prod (daily) so a drifted resizer contract surfaces as an alert, not a user-reported blank gallery. Document in `docs/operations.md`.
- [ ] **Step 3:** Commit.

**Phase 2 exit criteria:** prod serves and uploads from `kal-media-prod`; the resizer reads/writes it; the `storage_root`/`storage_driver` columns are gone; the window closes on a green smoke test. Per-tenant buckets are kept as cold backup (Decision 3) and retired later.

---

## Phase 3 — Hardening (integrity, reconciliation, lifecycle)

### Task 7: Add `content_hash` checksum column

**Files:**
- Create: `resources/migrations/20260718NNNNNN-add-media-content-hash.up.sql` / `.down.sql`
- Modify: `src/kaleidoscope/api/albums.clj` (compute + store SHA-256 on write)
- Test: `test/kaleidoscope/api/albums_test.clj`

- [ ] **Step 1: Up migration** — `ALTER TABLE photo_versions ADD COLUMN IF NOT EXISTS content_hash VARCHAR(71);` (nullable; `'sha256:'` + 64 hex). Down: `DROP COLUMN content_hash;`.
- [ ] **Step 2: Failing test** — a newly uploaded raw version has `content_hash` equal to `"sha256:" + sha256hex(bytes)`.
- [ ] **Step 3: Implement** — compute SHA-256 of the uploaded bytes in `new-image`, store on the version row. (Checksum only — the key stays the UUID path; this is not content-addressing.) **New uploads only — no corpus backfill:** existing photos keep a null `content_hash`. **This column ships with its reader:** reconciliation (Task 8) verifies stored bytes against `content_hash` for every row that has one, and skips rows that don't (the pre-Phase-3 corpus) — so the column does real work from its first write, rather than sitting unread as a hedge. Reconciliation still diffs *keys* independently of hashes; the hash check is an added integrity pass, not a dependency.
- [ ] **Step 4:** Run focused test → PASS.
- [ ] **Step 5:** Commit.

### Task 8: Offline reconciliation / reclamation job

> **Language decision (§8, operability).** The set-differences and safety gates are the single most dangerous operation in this plan — they decide which bytes get moved toward deletion by diffing the source of truth against stored objects. That logic does **not** belong in `bash + jq/awk`, where one unquoted variable, one field-split surprise on an odd key, or one silently-empty `jq` result mis-computes `orphans` and quarantines a live photo, with `set -euo pipefail` as the only seatbelt. We already run a JVM with the S3 client and DB access, so **the reconciliation logic lives in Clojure (`src/kaleidoscope/tasks/reconcile.clj`), fully unit-tested with pure functions over in-memory sets.** `scripts/media/reconcile` is a thin launcher (`clojure -X …`) — the *ephemeral glue* scripts stay bash because they're just AWS-CLI orchestration, but real logic gets a real language.

**Files:**
- Create: `src/kaleidoscope/tasks/reconcile.clj` (pure set-math + gates + effectful driver)
- Create: `test/kaleidoscope/tasks/reconcile_test.clj`
- Create: `scripts/media/reconcile` (thin `clojure -X` launcher only)
- Modify: `Taskfile.yml` (`media:reconcile`), `docs/operations.md`

**Interfaces:**
- Produces: a gated, reversible job. Inputs: an `S3 Inventory` manifest (`stored`) and the set of keys derivable from live `photo_versions` rows (`referenced`). Outputs: `orphans = stored − referenced` (moved to a `trash/` prefix, later lifecycle-expired), `dangling = referenced − stored` (alert only, possible data loss), and `mismatched` = rows whose `content_hash` disagrees with the stored object's checksum (alert; the integrity check that gives the Phase-3 column a present reader — §6/§7).
- Core is **pure and total**: `(reconcile-plan {:stored #{…} :referenced #{…} :hashes {key→hash} :object-hashes {key→hash} :last-referenced-count N})` → `{:orphans … :dangling … :mismatched … :gate (:ok | :abort-shrink | :abort-unhealthy)}`. No I/O in the core; the driver supplies the sets and executes the plan only when `:gate` is `:ok`.

- [ ] **Step 1: Write the failing test** (`reconcile_test.clj`) over the pure core — no S3, no DB:
  - `orphans = stored − referenced`; `dangling = referenced − stored`; disjoint, exhaustive.
  - `content_hash` verification: a key whose `:object-hashes` value ≠ its `:hashes` value appears in `:mismatched`; a key with no stored `content_hash` (pre-Phase-3 corpus) is **skipped**, never flagged.
  - **Shrink gate:** `referenced` count dropping > 10% vs `:last-referenced-count` yields `:gate :abort-shrink` and an **empty** orphan action set (refuse to quarantine on a suspicious index).
  - Orphans map to a `trash/`-prefixed destination, never a hard delete.
- [ ] **Step 2: Implement** `reconcile.clj`: the pure `reconcile-plan` above, plus an effectful driver that reads the S3 Inventory manifest and a DB query of derivable keys + `content_hash`, runs the DB health check, calls `reconcile-plan`, and — only when `:gate` is `:ok` — `CopyObject`s orphans to `trash/` then deletes the originals (bucket versioning makes even that reversible). Refuse to run on a failed DB health check.
- [ ] **Step 3:** Write `scripts/media/reconcile` as a thin launcher (`exec clojure -X:reconcile …`, passing env-supplied bucket + manifest path). Add `media:reconcile` to `Taskfile.yml`. Document in `docs/operations.md`: run cadence (monthly), the **"restore the index from PITR *before* reconciling"** rule (never reconcile against a corrupted index), bucket versioning + `trash/` lifecycle-expiry, and the abort-incomplete-multipart-upload lifecycle rule.
- [ ] **Step 4:** Run focused test → PASS. Commit.

### Task 9: Bucket lifecycle & versioning configuration

**Files:**
- Modify: `docs/operations.md` (and any IaC if present)

- [ ] **Step 1:** Enable **versioning** on the media bucket (reversible deletes).
- [ ] **Step 2:** Lifecycle rules: (a) abort incomplete multipart uploads after 7 days; (b) transition cold objects to a cheaper storage class (Intelligent-Tiering or IA→Glacier); (c) expire `trash/` after N weeks; (d) expire noncurrent versions after N days.
- [ ] **Step 3:** Document all rules in `docs/operations.md`. Commit.

**Phase 3 exit criteria:** every version row carries a verifiable checksum; a documented, gated, reversible reconciliation job exists; the bucket has versioning + lifecycle rules bounding orphan cost.

---

## Self-Review

- **Spec coverage:** object-storage rationale (§Rationale 1–2) ✅; keys not tied to drift, `storage_root`/`storage_driver` dropped via a migration co-shipped with the code that stops writing them (§3, Task 2) ✅; `:photos-folder` indirection removed (Task 2) ✅; tenancy/liveness in index (§4) ✅; ephemeral fix via per-env bucket + read-through to prod, no staging copy (§5, Decision 1, Phase 1) ✅; upload round-trip works in Phase 1 (Task 2) ✅; resize notify derives the `s3://…` URL from `write-location` (raw key computed once, prefix folded in), dead attributes dropped (Task 2); resizer verified to need no code/IAM change — it follows the URL bucket (Task 5 Step 3, per resizer analysis) ✅; downtime cutover — window chosen because downtime is cheap here (not because co-shipping made expand/contract unnecessary), code↔schema rollback coupling owned (Phase 2 intro, Decision 4) ✅; append-only + reclamation + index-corruption handling (§6, Tasks 8–9) ✅; content-addressing deferred, checksum on new uploads only *and consumed by reconciliation* (§7, Tasks 7–8) ✅.
- **Guarantees enforced, not asserted (§8):** the Phase-1 inert-build behavior and the resizer URL-only contract are CI tests (Task 2), not paragraphs; `write-location == put-file` target is pinned (Task 2); reader/writer isolation is structural in `ReadThroughFS` (Task 1); the orphan-bucket reaper is a real runnable task (Task 3b), not a hope (auto-scheduling deferred); the resize round-trip is an automated fitness function (Task 6), not only a manual curl; reconciliation set-math + gates are typed, tested Clojure (Task 8), not `jq/awk`. For each clever invariant, *a specific test turns red if it's violated.*
- **Placeholder scan:** the production bucket is decided as `kal-media-prod` (operator may rename); pre-Phase-2 the ephemeral read-through source is the pinned tenant's own bucket (`$TENANT`). `<known-photo-id>`, `<new-photo-id>`, `<slug>`, and `20260718NNNNNN` are intentional operator/runtime-supplied values — fill at execution time. No logic placeholders.
- **Type consistency:** `tenant/media-store` (const `"media"`) is defined in Task 2 and consumed in Tasks 2/5; `ReadThroughFS` (fields `writer` + ordered `readers`) is defined in Task 1 and composed in Task 2; `content_hash` column (Task 7) matches `"sha256:"`-prefixed values.

## Decisions (resolved 2026-07-18)

1. **Bucket topology — DECIDED: one production media bucket (`kal-media-prod`); each ephemeral env gets its own disposable media bucket and reads through to prod read-only.** Production serves and writes `kal-media-prod`. Each ephemeral env writes to a dedicated bucket `kal-eph-<slug>-media` (created on `up`, emptied+deleted on `down`) and reads through to prod media read-only via `ReadThroughFS(writer = per-env-bucket, readers = [per-env-bucket, prod])`. Before Phase 2 (prod media still in per-tenant buckets) an env pinned to `andrewslai.com` reads through to `s3://andrewslai.com`; after consolidation it reads through to `kal-media-prod`. **Why per-env buckets, not a shared bucket or a synced staging copy:** the bucket is the env's namespace (no prefix in any key), teardown is one whole-bucket delete (no per-env orphans to reconcile in a shared bucket), and creation is cheap because the bucket starts *empty* — existing photos come from prod via read-through, never copied. A synced staging copy would reintroduce copy-and-drift (stale → 404 on recent photos); a shared bucket would make ephemeral teardown a messy branch-aware reconciliation. **Accepted costs:** (a) **least privilege** — ephemeral IAM holds a *read-only* credential on prod media (throwaway infra can read prod media; narrows to one bucket after Phase 2), and now also `CreateBucket`/`DeleteBucket`/object read-write scoped to the `kal-eph-*-media` name pattern; (b) **the S3 account bucket ceiling** (~100 default, raisable) — fine for a handful of concurrent envs, but `down` must reliably delete, and the orphan-bucket reaper (`kal-eph-*-media` with no live Fly app) is a **real runnable task — Task 3b**, not an aspiration, run by hand when orphans are suspected (auto-scheduling deferred until we see the actual orphan rate), so failed teardowns can be cleaned up before they accumulate to the limit. Note deleting a bucket requires emptying it first, and `kal-eph-<slug>-media` must be globally unique (account-specific naming).

2. **Ephemeral writes — DECIDED: uploads supported, isolated in a per-env bucket.** An ephemeral env's media store is `ReadThroughFS` with `writer` = a plain `S3` store on its own bucket `kal-eph-<slug>-media` (no prefix — the bucket is the namespace), and `readers` = `[per-env-bucket, prod-media-read-only]`. New uploads land in the env's own bucket (disposed by deleting the bucket at teardown); the existing catalog reads through from prod. **Rationale:** `ReadThroughFS` (Task 1) makes "ephemeral writes never reach prod" a structural guarantee, and the per-env bucket makes teardown a single delete with no leak and keeps every key prefix-free. Chosen over read-only ephemeral because it lets an env exercise the full upload→view lifecycle; chosen over a shared write bucket because per-env buckets dispose cleanly (Decision 1).

3. **Retire per-tenant buckets — OPEN (recommendation: keep as cold backup).** After the Phase 2 soak, retain `andrewslai.com` / `caheriaguilar.com` / `sahiltalkingcents.com` media as a read-only cold-tier backup for one retention cycle before deleting, rather than deleting immediately. Confirm at Phase 2 close.

4. **Migration technique — DECIDED: one maintenance window, not Parallel Change (expand/contract).** We drop `storage_root`/`storage_driver` in the *same* build that stops writing them, flipped in a scheduled downtime window, rather than the zero-downtime expand/contract sequence (stop-writing release, soak, drop release). **The deciding reason is that downtime is cheap for this app** — a single-owner personal CMS where an owner-scheduled window costs essentially nothing — *not* that co-shipping the migration made expand/contract technically unnecessary (it doesn't; only accepting downtime does). **Accepted cost:** code and schema roll back together — the coupled build has no independent code-vs-schema rollback granularity. Mitigations: lossless migration `down` (re-add + backfill from `hostname`/`'s3'`), a scripted rollback lever (Task 5), and a pre-window DB snapshot. **Revisit trigger:** if this app ever acquires a real availability SLO, switch to expand/contract and delete the window — this is the first decision to reopen.
