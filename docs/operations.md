# Operations

Operational concerns for running Kaleidoscope in production. This document
covers how the app is set up to operate, the guardrails around that setup, and
the decisions behind them.

> **Maintenance:** Keep this document current. Any change to deployment
> (`fly.toml`, `bin/` deploy scripts, Docker/build, secrets/env) or to the
> Taskfile / `bin/` interface must be reflected here in the same change.

## Production deployment

`task deploy` is the production release path. It runs, in order:

1. `task test` — full suite; a failing test aborts the deploy.
2. `task build:uberjar` — compile + package the standalone JAR.
3. `task db:migrate ENV=.env.fly.prod` — apply pending migrations to the prod
   Neon DB.
4. `./bin/deploy-image` (`flyctl deploy`) — deploy to Fly.io.

**Migrations run before the Fly deploy, not after.** The schema must be ready
before the new code boots. Shipping code ahead of its migrations is exactly what
broke `POST /recipes/scrape` in production (the deployed code wrote `raw_scrapes`
columns — `source_kind`, `raw_content` — that its migration hadn't yet added, so
every scrape 500'd). Folding the migration into `task deploy` closes that gap.

The migration step targets `.env.fly.prod` by default; override with
`task deploy DEPLOY_ENV=<env-file>` to release against a different target. Because
the migration connects to managed Postgres, the migration runner
(`persistence.rdbms.live-pg/pg-conn`) requests TLS via `KALEIDOSCOPE_DB_SSL_MODE`
(default `require`), mirroring the runtime pool.

Migrations are additive/forward-compatible by convention so the brief window
where the migrated schema serves the still-running old code is safe. Avoid
destructive column drops/renames in the same deploy as the code that stops using
them — split those across two deploys.

## Squashing migrations

Migratus (>= 1.5) can collapse a contiguous run of **already-applied** migrations
into a single file, via three Taskfile subcommands. Squashing is a manual, one-time
repo operation — it is never part of `task deploy`.

Squash is two-sided: `db:squash:create` rewrites the repo files (once), while
`db:squash:apply` reconciles a single database's `schema_migrations` table (run once
per durable database). The new file reuses the **highest id** in the range, so a DB
that already applied the originals never re-runs it, and a fresh DB simply runs the
one combined file.

**Hard precondition:** every durable environment must have applied the *entire* range
before you squash. `db:squash:create` deletes the granular files, so a database left
mid-range would desync permanently. `squash-create`/`squash-list` themselves refuse
to run if any migration in range is unapplied in the connected DB.

Squashing `FROM`..`TO`, in this exact order:

1. **Bring every durable DB current.** `task db:migrate ENV=.env.aws` (prod Neon) and
   any other long-lived DB, until `task db:squash:list ENV=<env> -- FROM TO` returns
   clean against each.
2. **Preview (read-only):** `task db:squash:list -- FROM TO`.
3. **Rewrite files against a fully-applied local DB:** `task db:migrate` then
   `task db:squash:create -- FROM TO consolidate-<slug>`. Review the generated file
   and commit the deletions + new file together.
4. **Reconcile each durable DB:** `task db:squash:apply ENV=.env.aws -- FROM TO consolidate-<slug>`
   (repeat per durable env). This mutates only the tracking table; it runs no SQL.
5. **Disposable DBs need nothing.** Local embedded, CI, and ephemeral Neon branches are
   created fresh and run the new combined file. For your own dev DB, `task db:reset` is
   simpler than `squash:apply`.

The squash `NAME` must not be purely numeric (ids are parsed as numbers; kebab-case
names are left as strings). The underlying runner is `bin/db-squash`.

## Synthetic monitoring (Checkly)

Synthetic checks live in `checkly/` as a standard Playwright project
(`checkly/playwright.config.ts`) wrapped by thin `PlaywrightCheck` constructs in
`checkly/__checks__/suites.check.ts`. Each suite is one Playwright project and
answers one question about one feature; a suite's name is its diagnosis.

**Run locally (no Checkly account):**
```bash
cd checkly
# read-only suites against production
npx playwright test --project=liveness --project=homepage --project=auth-boundary --project=articles
# write / login suites need Auth0 creds; projects and scoring make paid Claude calls
set -a; source ../.env.fly.staging; set +a
ENVIRONMENT_URL=https://kal-eph-<slug>.fly.dev npx playwright test --project=scoring
```

**Cost tags.** Suites carry `no-spend` (no paid external call) or `spends` (makes
a real, paid Claude call). `POST /projects` fans out to default-definition
scoring and the default workflow, so project creation is paid too — the
`projects` suite is tagged `spends` alongside `scoring`. `checkly test --tags`
is include-only.

Run the suite against an ephemeral env on demand with `task ephemeral:checkly-test`.
`NAME` is optional: with no `NAME` it lists the deployed `kal-eph-*` Fly apps and
prompts you to pick one (auto-selecting when there's only one), so it needs a TTY.
Pass `NAME=<slug>` to skip the prompt or run non-interactively (e.g. in CI). The
`ephemeral:down`, `ephemeral:build-frontend`, and `ephemeral:smoke-test` tasks share
this same optional-`NAME`, prompt-if-omitted behavior; the remaining `ephemeral:*`
tasks (`up`, `provision-db`, `deploy-app`, `seed-tenant-assets`) still require an
explicit `NAME`.

`ephemeral:build-frontend` only supplies staging AWS creds and a clean `npm ci`,
then delegates the actual build and S3 sync to `npm run ephemeral:deploy` in the
kaleidoscope-ui repo — the frontend owns its `vite build` command and dist path,
and must expose that `ephemeral:deploy` script.

The runner exports `CHECKLY_TEST_ENVIRONMENT=<slug>` so the recorded test session is
labeled with the ephemeral env in Checkly's dashboard (the Environment column is
blank without it).

| Context | Command | Runs |
|---|---|---|
| Ephemeral `up` | `checkly test --tags no-spend` (in `scripts/ephemeral/checkly-test`) | 6 free suites |
| On-demand LLM check | `checkly test --tags spends --env ENVIRONMENT_URL=<url>` | projects + scoring (paid) |
| Production monitors | `checkly deploy` | all 8; scoring daily, rest every 6h |

**Deployed monitors need these Checkly environment variables** (set once in the
Checkly account, not committed): `AUTH0_CLIENT_ID`, `AUTH0_CLIENT_SECRET`.
`ENVIRONMENT_URL` is omitted in production so checks default to the live site.

## Ephemeral tenancy / asset isolation

An ephemeral env is a single Fly app, so it can't serve every production
tenant by `Host` header the way prod does — it **pins** itself to exactly one
tenant hostname and serves that tenant's content in isolation.

- **`TENANT` input.** `TENANT=<hostname>` (e.g. `caheriaguilar.com`) selects
  the impersonated tenant. Defaults to `DEFAULT_TENANT` (`andrewslai.com`) when
  unset. It's validated against the tenant registry, `resources/tenants.json`
  — an unknown hostname fails loudly (`known_tenant?` in
  `scripts/ephemeral/lib.sh`) rather than silently seeding an empty or wrong
  S3 prefix. `resources/tenants.json` is the one place to onboard a new
  tenant hostname for ephemeral use.
- **Fixed resolver.** `deploy-app` sets `KALEIDOSCOPE_TENANT_RESOLVER_TYPE=fixed`
  and `KALEIDOSCOPE_TENANT=$TENANT`, so the backend resolves every request to
  the pinned tenant instead of inspecting the `Host` header. Every
  tenant-scoped content handler — articles, recipes, themes, audiences,
  photos — reads and writes against this one pinned tenant's DB rows, so the
  Neon branch must actually contain that tenant's data (see the DB-seeding
  prerequisite below).
- **Isolated site chrome.** `/static/*` is served from
  `s3://kal-ephemeral/tenant-assets/<slug>/` — an S3 prefix scoped to this one
  ephemeral env (`KALEIDOSCOPE_TENANT_ASSET_BUCKET`/`_PREFIX`), never the real
  per-tenant bucket from `resources/tenants.json`.
  `task ephemeral:seed-tenant-assets NAME=<slug> TENANT=<hostname>` (run by
  `up`, before `deploy-app`) syncs
  `test-resources/ephemeral-sample-assets/<hostname>/` into that prefix.
- **Photos: per-env media bucket + read-through (no seeding).** Photos are
  served and uploaded via a single media store (`KALEIDOSCOPE_MEDIA_BUCKET`),
  keyed by the object's intrinsic identity (`media/<uuid>/<category>.<ext>`) —
  the same key resolves in every environment. Each ephemeral env gets its **own
  disposable bucket** `kal-eph-<slug>-media` (created on `up`, emptied+deleted
  on `down` — the bucket **is** the namespace, so keys carry no prefix) and
  **reads through to prod media read-only** via
  `KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET`. Existing photos load with **zero
  copy/seed** — the branched DB's `media/<uuid>/…` keys resolve against the env's
  own bucket for its uploads, else the immutable prod corpus. New uploads land
  in the env's own bucket and are disposed whole at teardown. The read-through
  source (`PROD_MEDIA_BUCKET`) defaults to `kal-media-prod`, which the ephemeral
  IAM read grant is scoped to (`iac/artifact-bucket/media.tf`) — so consolidate
  into `kal-media-prod` before relying on read-through, and only override
  `PROD_MEDIA_BUCKET` if you have also widened that read grant. Isolation is
  structural: the media store is a `ReadThroughFS`
  whose *writer* is the env's own bucket, so an ephemeral env can never mutate
  the shared read-only prod media (see `persistence/filesystem/read_through.clj`).
- **IAM grants (ephemeral user).** Codified in `iac/artifact-bucket/media.tf`
  (the `kal-ephemeral-media-s3` inline policy on the existing
  `kaleidoscope_ephemeral` user): (a) `s3:CreateBucket`/`s3:DeleteBucket` +
  object read-write scoped to the `kal-eph-*-media` name pattern (per-env bucket
  lifecycle + uploads), and (b) **read-only** (`s3:GetObject`/`s3:ListBucket`) on
  `kal-media-prod` — the least-privilege tradeoff of read-through. Because the
  read grant is scoped to `kal-media-prod` only, ephemeral read-through resolves
  existing photos against it, so deploy with `PROD_MEDIA_BUCKET=kal-media-prod`
  (and consolidate into it first).
- **Notifier disabled.** `deploy-app` sets `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none`
  so any image upload against an ephemeral env can't trigger the production
  resize/notification topic.
- **Orphaned media buckets.** A *failed* teardown (crashed `down`, killed CI
  job) can leave a `kal-eph-<slug>-media` bucket behind — each holds a live
  read credential on prod media and counts against the ~100/account bucket
  ceiling. `task ephemeral:reap` finds and (with `--apply`) deletes per-env
  media buckets whose Fly app no longer exists; run it by hand when orphans are
  suspected (auto-scheduling is a deferred follow-up).
- **`KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS`/`_BUCKET`/`_PREFIX` are removed** —
  superseded by the fixed-resolver + tenant-asset vars above.
- **DB-seeding prerequisite.** The Neon branch (`provision-db`) must already
  contain the pinned tenant's rows — especially `themes` — or the env boots
  fine but renders empty content for that tenant. Ephemeral envs branch from
  `staging`, so keep `staging` seeded with every onboarded tenant's data.

## Media object storage

Photos live in a single per-environment object store, keyed by the object's
intrinsic identity (`media/<photo-id>/<category>.<ext>`) — never by tenant,
hostname, environment, or bucket. Location is `f(intrinsic-id, env-config)`: the
object supplies the key, the environment supplies the bucket.

- **Env vars.** `KALEIDOSCOPE_MEDIA_BUCKET` selects the bucket photos serve and
  upload from (prod: `kal-media-prod`). When unset, the app falls back to the
  per-tenant asset adapter — prod runs this way until the Phase-2 cutover, so
  deploying the media code to prod is inert until the bucket is set.
  `KALEIDOSCOPE_MEDIA_FALLBACK_BUCKET` (optional, ephemeral only) makes the media
  store a read-through overlay that reads prod media read-only after its own
  bucket (see "Ephemeral tenancy / asset isolation").
- **Consolidation (Phase-2 prerequisite).** Prod media currently lives in the
  per-tenant buckets (`andrewslai.com`, `caheriaguilar.com`,
  `sahiltalkingcents.com`, `wedding`). `task media:consolidate`
  (`scripts/media/consolidate-buckets`) server-side-syncs each bucket's `media/`
  prefix into `s3://kal-media-prod/media/`. Because keys are UUID paths, the
  buckets merge losslessly (no `--delete`, no collisions). It is idempotent —
  run it incrementally before the maintenance window, then once more in-window
  to catch the delta — and verifies a sample of objects, failing loudly on any
  miss.
- **The resizer follows the URL.** On upload the app derives the raw object's
  `s3://bucket/key` from the store's `write-location` and puts that in the
  resize-notify message body. The deployed Lambda parses only that URL and writes
  renditions into that same bucket; it reads no message attributes and its IAM
  already covers `s3:::*`, so **the resizer needs no code or IAM change** when the
  media bucket flips — it follows whatever bucket the URL names. The
  `photo_resize_contract_test` pins this message shape in CI.
- **Resize round-trip fitness function.** `task media:verify-resize`
  (`scripts/media/verify-resize-roundtrip`, `TARGET_URL=<env>`) uploads a photo
  and polls until its resized rendition appears, failing loudly and naming the
  broken hop (notify shape, SNS→SQS subscription, or the resizer Lambda/IAM). It
  is the authoritative reopen gate for the Phase-2 window and should run on a
  schedule against prod so a drifted resizer contract surfaces as an alert, not
  a user-reported blank gallery. Needs `AUTH0_CLIENT_ID`/`AUTH0_CLIENT_SECRET`.

### Phase-2 prod cutover runbook (one maintenance window)

The media code (media store, `new-image`'s `write-location` notify, the
read/write routing) and the `DROP COLUMN storage_root/storage_driver` migration
already shipped on `master`. Prod may already be running that build but is
**inert** with `KALEIDOSCOPE_MEDIA_BUCKET` unset — old per-tenant adapter, notify
URL unchanged, columns harmlessly dropped. This window is **operational only**.
We accept brief downtime (single-owner CMS) rather than an expand/contract dance;
the tradeoff is that code and schema roll back together (the migration `down` is
lossless — see below). Take a prod DB snapshot before starting.

1. **Quiesce.** Stop the prod app (or enable maintenance mode) so no photo is
   written mid-cutover.
2. **Final consolidation.** `task media:consolidate` once more so `kal-media-prod`
   holds every object written since the last incremental sync; the script's
   sample verification must pass.
3. **Resizer — verify only, nothing to deploy.** The resizer is bucket-agnostic
   (parses `s3://bucket/key` from the message body, writes renditions into that
   same bucket, IAM already grants `s3:::*`). Confirm `new-image`'s URL is built
   from `write-location` (so it names `kal-media-prod` post-flip) and the
   SNS→SQS subscription still delivers. There is no resizer cutover ordering — it
   follows whatever bucket the URL names.
4. **Deploy the current build to prod.** Applies the `DROP COLUMN` migration
   (co-shipped with the code that stopped writing the columns → safe) and ships
   the media-store write + `write-location` `s3://…` notify.
5. **Flip.** Set `KALEIDOSCOPE_MEDIA_BUCKET=kal-media-prod` in prod secrets and
   restart. Prod now serves and writes the single bucket and notifies the resizer
   with `bucket=kal-media-prod`.
6. **Gate + reopen.** Quick eyeball:
   ```bash
   curl -s -o /dev/null -w "existing: %{http_code}\n" "https://andrewslai.com/v2/photos/<known-id>/raw.JPG"   # 200
   curl -s -F "file=@sample.jpg" "https://andrewslai.com/v2/photos"                                           # 201; note id
   curl -s -o /dev/null -w "gallery: %{http_code}\n" "https://andrewslai.com/v2/photos/<id>/gallery.jpg"      # 200 once the resizer runs
   ```
   The **authoritative reopen gate is `task media:verify-resize`** — reopen only
   when it exits green.

- **Rollback lever.** If the smoke test fails, unset `KALEIDOSCOPE_MEDIA_BUCKET`
  (prod reverts to the per-tenant bucket + old adapter; the notify URL is
  unchanged so the old resizer still works). If the schema must revert too, run
  the migration `down` (`resources/migrations/…-drop-photo-storage-location.down.sql`
  — re-add + backfill `storage_root = hostname`, `storage_driver = 's3'`;
  derivable, lossless). The pre-window DB snapshot is the backstop.
- **Per-tenant buckets** are kept as a read-only cold backup for one retention
  cycle after the soak, then deleted (confirm at Phase-2 close).

> **⚠️ GUARD — do NOT delete the per-tenant buckets yet.** They still serve
> **article-embedded images**. The Phase-2 flip only re-points the photo API
> (`/v2/photos/*`) at `kal-media-prod`; the generic **`/media/*` route**
> (`http_api/kaleidoscope.clj`) still resolves via the tenant's per-tenant
> `:asset-store` adapter (the hostname bucket), so `<img src="/media/processed/…">`
> in articles keeps reading from the per-tenant buckets. Deleting those buckets
> 404s every article image. Retiring them is blocked on a **separate
> `article-embedded-asset-acl` plan** that must (1) re-point `/media/*` at the
> media store — the bytes are already in `kal-media-prod` via `media:consolidate`,
> which syncs all `media/…` keys including `media/processed/…` — and (2) decide
> the access-control model for those assets (`/media/*` GET is currently blanket
> `public-access`; audience-scoped articles may need authorized images). Only
> after that plan ships may the per-tenant buckets be retired.

### Reconciliation / reclamation (offline)

The store is append-only: deleting a photo drops its row and leaves the blob (an
orphan). Reclamation is a periodic offline job, never on the write path.

- **`task media:reconcile`** (`scripts/media/reconcile` → `tasks/reconcile.clj`)
  diffs the source of truth (live `photo_versions` rows) against the stored
  objects: `orphans = stored − referenced` (quarantined to a `trash/` prefix,
  later lifecycle-expired — **never hard-deleted**), `dangling = referenced −
  stored` (alert: possible data loss), and `mismatched` (rows whose
  `content_hash` disagrees with the stored bytes — the integrity pass that gives
  the checksum column a present reader).
- **The dangerous logic is typed and tested.** The set-math and safety gates live
  in Clojure (`reconcile-plan`, unit-tested over in-memory sets), not bash — the
  launcher is a thin `clojure -M -m` wrapper.
- **Gated + reversible.** It refuses to quarantine when the referenced set shrank
  suspiciously (>10% vs the last run) or a DB health check fails; orphans go to
  `trash/` (reversible via bucket versioning), not a hard delete. Dry-run by
  default; `APPLY=1` to act.
- **Inputs.** `KALEIDOSCOPE_MEDIA_BUCKET`, `RECONCILE_STORED_KEYS` (a
  newline-delimited keys file materialized from the bucket's S3 Inventory export
  or `aws s3 ls s3://<bucket>/ --recursive | awk '{print $4}'`), and
  `KALEIDOSCOPE_DB_*`. Optional: `RECONCILE_LAST_REFERENCED_COUNT` (shrink gate),
  `RECONCILE_VERIFY_HASHES=1` (re-head + checksum each hashed object).
- **NEVER reconcile against a corrupted index.** The index is the sole source of
  truth for liveness/ownership — restore it from point-in-time recovery *first*.
  Run cadence: monthly.

### Bucket lifecycle & versioning

Configured in `iac/artifact-bucket/media.tf` (the `kal_media_prod` bucket +
versioning + lifecycle resources):

- **Versioning: enabled** — makes deletes (including reconciliation's) reversible.
- **Lifecycle rules:** (a) abort incomplete multipart uploads after 7 days;
  (b) transition every object to Intelligent-Tiering at day 0 — bounds even
  orphaned bytes to cold-tier pricing; (c) expire the `trash/` prefix after 28
  days; (d) expire noncurrent versions after 30 days.

## Claude Code workspaces

Kaleidoscope's AI features (the workflow engine and project scorer) call the
Anthropic API. Rather than running Claude Code against the same Anthropic
account and API key used for production traffic, we provision a **separate,
dedicated Claude Code workspace** with its own key.

That workspace is capped at a **hard spend limit of $10 per month**.

**Why a separate workspace with a hard cap:**

- **Blast radius.** A Claude Code API key can end up in more places than a
  production key — shell history, local config, CI logs, a subagent's
  environment. Isolating it in its own workspace means a leaked or misused key
  can never draw down the production Anthropic budget or touch production usage.
- **The cap bounds the damage.** $10/month is the ceiling on what a leaked key
  can cost before it's cut off. It's high enough for normal development use and
  low enough that a compromised key is an annoyance, not an incident.
- **Clean attribution.** Keeping the workspaces separate makes Claude Code
  spend legible on its own, distinct from the app's production API usage.

**Operational notes:**

- The workspace key is only for development tooling (Claude Code). It is **not**
  the key the deployed app uses for its own Anthropic calls — that key lives in
  Fly.io secrets (`ANTHROPIC_API_KEY`) and is scoped to the production
  workspace/budget.
- If the $10 cap is hit mid-month, Claude Code requests will start failing.
  That's the intended signal — investigate the spend before raising the limit,
  don't reflexively bump it.
- If the workspace key is ever suspected leaked, rotate it in that workspace;
  no production credential or budget is affected.
