# Tenant resolution: fixing ephemeral assets, and a north-star for multi-tenancy

Date: 2026-07-16
Status: Design (spec) — pending review before an implementation plan is written.

## Summary

Assets (and content) don't load in ephemeral Fly environments. The proximate
cause is that an ephemeral env serves under a synthetic host
(`kal-eph-<slug>.fly.dev`) while its data and assets are keyed to a real tenant
hostname (`andrewslai.com`). The env is **internally inconsistent**: the host it
serves on, the `hostname` its rows are keyed by, and the bucket its assets live
in disagree.

The root design issue is that **`hostname` does three jobs at once** — tenant
*identity*, request *routing*, and asset *address*. Every workaround we
considered (rewriting the Host header in middleware, bulk-`UPDATE`-ing the
`hostname` column in the ephemeral DB branch, Postgres RLS) is a way to cope
with that conflation rather than fix it.

This spec proposes a small, honest **near-term seam** that unblocks ephemeral —
resolve the tenant *once* at the edge, as an explicit value that flows through
the request — and documents a **north-star** multi-tenancy model for when
multi-tenancy is genuinely the task, reconciled with the prior (unshipped)
`db-backed-multi-tenancy` plan.

---

## Problem

Reported: "assets are not loading for ephemeral environments."

Concretely, in an ephemeral env (`https://kal-eph-<slug>.fly.dev`):

- `/`, `/index.html`, `/assets/*` **work** — these routes force the Host to
  `kaleidoscope.client` (`http_api/kaleidoscope.clj:138-147` via
  `wrap-force-host`, `middleware.clj:229-249`), so they serve the per-env SPA
  build that `scripts/ephemeral/build-frontend` pushed to
  `s3://kal-ephemeral/eph-<slug>/`.
- `/favicon.ico`, `/static/*`, `/media/*` **404** — these carry no forced host,
  so `http_utils/get-resource` (`http_api/http_utils.clj:35-55`) looks up the
  static adapter by the *real* Host header (`kal-eph-<slug>.fly.dev`). That
  resolves to the ephemeral-host adapter (`env.clj:234-241`), whose prefix
  `eph-<slug>/` contains only the SPA build — not the tenant's static/media.

A secondary problem, discovered while diagnosing: even if assets loaded, **most
content wouldn't render.** The Neon branch is cut from `staging`
(`scripts/ephemeral/lib.sh:43`), whose rows are keyed to real hostnames like
`andrewslai.com`, but every HTTP handler scopes its DB queries by
`hu/get-host` — which in an ephemeral env returns `kal-eph-<slug>.fly.dev`. So
articles, themes, audiences, recipes all query for a hostname that has no rows.

## Root cause

`hostname` (the `Host` header, and the `hostname` column it's stored in) is
**three concerns fused into one string**:

1. **Identity** — *whose* content a row is (stable, meaningful, at rest).
2. **Routing** — *how* an inbound request selects a tenant (the Host header).
3. **Address** — *where* the tenant's bytes live (the S3 bucket name).

Prod works because reality aligns all three: host `andrewslai.com` → rows keyed
`andrewslai.com` → bucket `andrewslai.com`. The "host == tenant" rule *just
works* only because nothing has pulled the three apart. Ephemeral pulls routing
(a different host) away from identity and address, and everything keyed off the
fused value breaks at once.

The invariant to restore: **an environment must agree with itself** — the
identity it represents, the rows it reads, and the assets it serves must be one
tenant. There are only two ways to restore it: make the app tolerant of the
mismatch (resolve tenant independently of host), or make the environment
genuinely consistent (align its data/assets to its host). This spec takes the
first path for the near term because it adds no destructive data step and keeps
one code path across all environments.

## Prior art (must reconcile, not contradict)

- `plans/2026-07-02-multi-tenant-ownership-consolidation/PLAN.md` (**shipped** →
  `persistence/ownership.clj`): consolidated *user* ownership into a data-driven,
  required-scoping API (`get-owned`/`update-owned!`/`delete-owned!`). Crucially,
  `ownership.clj` **already distinguishes two axes** — `:owner-col` (user) and
  `:site-col` (hostname) — and **already enforces required site-scoping** for
  mutations (`require-site!`). The "required-argument chokepoint" this spec
  wants for the site axis already exists for the owner axis.
- `plans/2026-07-04-db-backed-multi-tenancy/PLAN.md` (**not shipped** — no
  `sites`/`tenants` migration exists): proposed a DB-backed `sites` registry, a
  `SiteRegistry` protocol selected at boot, and rewiring the static-adapter map
  to read from it. It **deliberately kept `hostname` as the join key** (no
  surrogate `site_id` FK) and left legacy CMS `hostname` columns out of scope.
  The north-star below revives this and treats "surrogate id vs hostname-as-key"
  as an *open* question, not a settled reversal.
- `plans/2026-07-03-ephemeral-fly-environment/PLAN.md` (**shipped**): added the
  `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS/_BUCKET/_PREFIX` overlay and S3 `:prefix`
  support. The near-term seam **supersedes the `EPHEMERAL_HOST_*` trio** for the
  "represent a real tenant" use case (see below), while keeping the separate
  `KALEIDOSCOPE_CLIENT_BUCKET/_PREFIX` mechanism that serves the per-env SPA
  shell.

## Goals

- **Staging/ephemeral environments cannot read or write production data** —
  assets or DB. (Explicit requirement.) DB is already isolated per-env (Neon
  branch); assets become isolated via a seeded per-env S3 prefix.
- Ephemeral envs load per-tenant assets (`favicon`, `static`, `media`) — the
  reported bug.
- Ephemeral envs render per-tenant content (themes, articles, …) for one pinned
  tenant.
- **Zero behavior change in prod and local.**
- No destructive data rewrite; the `hostname` at rest stays honest.
- No ambient/mutable request state (no header rewriting, no connection GUCs).
- One code path in every environment (differ by *value*, not by which branch is
  live).

## Non-goals (near term)

- Renaming/migrating the legacy CMS `hostname` columns (out of scope per
  `CLAUDE.md` sharp edge #3 unless multi-tenancy is the explicit task).
- A DB-backed site registry / admin CRUD (that is the north-star / the unshipped
  2026-07-04 plan).
- Serving *multiple* tenants from a single ephemeral env (we pin exactly one).
- Row-Level Security. Rejected — see Rejected alternatives.
- Unifying the CMS site axis with the workflow/account (user) axis. They are
  already separate in `ownership.clj`; keep them separate.

---

## Near-term design — the resolution seam

### The one new idea

Introduce a single, pluggable resolution point for "the site-scoping value,"
which today is hard-derived from `hu/get-host` at ~40 call sites. Resolve it
**once**, early, and let it **flow as an explicit value** on the request.

We are *not* introducing a rich tenant object yet. The near-term tenant value is
minimal — enough to carry the scoping hostname and select the asset adapter.

### New namespace: `kaleidoscope.http-api.tenant`

The resolver puts **two sibling values** on the request — deliberately separate,
because they are two different concerns: identity (which tenant, for DB scoping)
and placement (which store, for files). They coincide in prod and diverge in
ephemeral.

```clojure
;; Resolver → {:tenant <identity string>  :asset-store <store name>}
;; merged onto the request as two top-level keys (NOT nested — identity and
;; placement are siblings).
(defn wrap-resolve-tenant [resolve-fn]
  (fn [handler] (fn [request] (handler (merge request (resolve-fn request))))))

;; http_utils accessors:
(defn site-value [request]  (or (:tenant request) (get-host request)))       ; DB identity
(defn asset-store [request] (or (::forced-store request) (:asset-store request))) ; file store name
```

`resolve-fn` is a strategy selected at boot in `init/env.clj`, like the
db/auth/storage/scorer backends:

- **`host` (default; prod + local):** `{:tenant host :asset-store host}` — both
  the Host header. Behaviour-identical to today.
- **`fixed` (ephemeral):** `{:tenant KALEIDOSCOPE_TENANT :asset-store <isolated
  store name>}` — identity pinned for the DB, placement pointed at the isolated
  store, independently.

### Why this fixes ephemeral with no data rewrite

With `fixed` → `:tenant = "andrewslai.com"`, `:asset-store = "ephemeral-tenant-assets"`:

- **Assets (isolated):** `get-resource` serves files from `(asset-store request)`.
  The isolated store is a **normal named entry** in the adapters map
  (`ephemeral-tenant-assets` → `KALEIDOSCOPE_TENANT_ASSET_BUCKET`/`_PREFIX` =
  `kal-ephemeral` / `tenant-assets/<slug>/`), seeded with sample assets. No map
  override — the resolver simply points `:asset-store` at it. Reads **and** writes
  stay in that prefix; prod media is never touched. The `EPHEMERAL_HOST_*` overlay
  is retired.
- **Content:** handlers scope DB queries by `site-value = "andrewslai.com"`, and
  the `staging`-derived branch already has `andrewslai.com` rows. The data keeps
  its honest identity; the *env* is told which tenant it represents. No `UPDATE`,
  no header rewrite.

Because `:asset-store` is resolved independently of `:tenant` in ephemeral, **no
pinned identity can ever reach a prod bucket for assets** — the `.localhost`
"don't make it pinnable" worry dissolves; `known_tenant?` is a typo-check, not a
safety boundary.

### Consuming the values — one decision, no chain

**"Which store serves `GET /media/x`?"** is now a single expression:
`(asset-store request)` = `(or ::forced-store :asset-store)`. Shared-shell routes
(`/`, `/assets/*`) name their store via route data `:store "kaleidoscope.client"`
(stamped as `::forced-store` by `wrap-force-store`); everything else uses the
store resolved once at the edge. There is no `resource-bucket` fallback chain, no
`site-value`-as-bucket double duty, and no Host fallback (the default not-found
handler sets `::forced-store` explicitly).

**`:asset-store` carries a store *name*** (a key into the adapters map), not the
adapter object — a deliberate choice: names are loggable, testable as plain
strings, and keep the isolated store a normal registry entry.

**Phase 2 — content.** Mechanically replace `(hu/get-host request)` →
`(hu/site-value request)` across `http_api/{articles,recipes,themes,audiences,photo}.clj`.
Behavior-preserving in prod. Photo additionally selects its **upload adapter** via
`(hu/asset-store request)`, so ephemeral uploads write to the isolated store.

`api/` and `persistence/` are **untouched** — they take `:hostname` as plain data;
the string now originates from `site-value` instead of `get-host`.

### Ephemeral deploy changes (`scripts/ephemeral/deploy-app`)

- Add a `TENANT` input (validated against the known tenant set; default
  `andrewslai.com`). Surface it through `Taskfile.yml` (`ephemeral:*`, `env:*`)
  and `scripts/ephemeral/up`.
- Set secrets `KALEIDOSCOPE_TENANT_RESOLVER_TYPE=fixed`,
  `KALEIDOSCOPE_TENANT="$TENANT"`,
  `KALEIDOSCOPE_TENANT_ASSET_BUCKET=kal-ephemeral`,
  `KALEIDOSCOPE_TENANT_ASSET_PREFIX=tenant-assets/<slug>/`, and
  `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none`.
- **Drop** `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS/_BUCKET/_PREFIX` (superseded by the
  tenant-asset override).
- **Keep** `KALEIDOSCOPE_CLIENT_BUCKET/_PREFIX` — the per-env SPA shell is a
  separate concern and still points at `eph-<slug>/` (distinct from the
  `tenant-assets/<slug>/` asset prefix, so the two syncs never collide).
- Add a `seed-tenant-assets` step (run by `up` before `deploy-app`) that syncs
  `test-resources/ephemeral-sample-assets/<tenant>/` → the asset prefix.
- Guardrail: fail the deploy if `TENANT` is not a known tenant, so a typo can't
  silently 404 the whole env; warn if the asset prefix is unseeded.

### Write isolation — resolved: isolate the whole store

**Decision (2026-07-16):** staging environments must not be able to affect
production data. Rather than read-through-with-a-write-guard, the pinned tenant's
**entire** asset store is isolated: the `s3` launcher points its adapter at
`s3://kal-ephemeral/tenant-assets/<slug>/`, seeded with sample assets. Reads and
writes both land there; the production tenant bucket is never touched. Uploads
therefore **work** in ephemeral (useful for exercising the upload flow) and are
structurally safe — no read-only mode, no split read/write target, no per-route
gating. Read-only/split-read-write were rejected as band-aids for insufficient
test-environment scaffolding.

To keep uploads from triggering production infrastructure, the ephemeral env also
sets `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none` (default is `sns`, which would
publish to the prod resize topic).

### Seeding dependency (the real prerequisite for content)

An ephemeral env is only as useful as the data in its branch. `fixed` scoping to
`andrewslai.com` renders a consistent-but-empty site if `staging` has no
`andrewslai.com` rows (especially `themes` — the site won't style without it).

- Confirm `staging` contains rows for each pinnable tenant, or add a seed step.
- Prefer a **representative / sanitized fixture** over a raw prod dump: real prod
  data carries PII, and `.env.fly.staging` already holds plaintext secrets —
  don't compound that by flowing real user data into disposable envs.

This seeding work is **orthogonal** to the resolution seam and can proceed
independently.

### Persistence

**No migration.** The `hostname` column keeps storing the tenant's canonical
value; `site-value` supplies that same string. The column name is mildly
under-descriptive (it is effectively a tenant id) but it is a value at rest, and
a rename is cosmetic, expensive (≈10 tables), and explicitly out of scope.

---

## North-star design (documented, deferred)

Written down so the direction isn't lost. **Not** part of the ephemeral fix.
Undertake when multi-tenancy is the explicit task (e.g. onboarding a real new
tenant/domain, or the API/M2M surface needs per-tenant scoping).

### 1. Separate the three jobs

- **Identity** — a stable tenant id. *Open question:* a human slug (`:andrewslai`)
  vs an opaque surrogate `site_id UUID`. The 2026-07-04 plan chose to keep
  `hostname` as the key; a slug is the lightest way to separate identity from
  address without a surrogate-key management layer. Surrogate ids earn their keep
  only when identity must survive renames/domain-moves/merges — evaluate against
  actual churn (near zero today).
- **Routing** — a many-hosts-to-one-tenant map (apex, `www`, `.localhost`, the
  `wedding` subdomain, future custom domains are just entries). Host becomes *one*
  routing input, not identity. Resolution stays pluggable, so non-browser
  surfaces can resolve by a different signal (see axes below).
- **Address / placement** — per-environment bindings (bucket/prefix, and a
  distinct write target). This is where read-through vs. copy and write-isolation
  become *values*, not code paths. Shared stores (`kaleidoscope.client`,
  `kaleidoscope.pub`) are **named stores addressed directly**, not tenants —
  which retires the `wrap-force-host` mechanism.

### 2. Registry: file-backed now, DB-backed later (revive 2026-07-04)

**Near term (in this plan):** the tenant registry is extracted to a single data
file, `resources/tenants.json`, read by both the Clojure `s3` launcher (to build
adapters) and `scripts/ephemeral/lib.sh` (via `jq`, to validate the pinned
`TENANT`). One source of truth — no tenant list is duplicated across code and
scripts, and the hardcoded `s3`-launcher map the 2026-07-04 plan flagged is
de-hardcoded. This is the north-star `SiteRegistry` with a **file** backing.

**North star:** adopt the 2026-07-04 plan's `sites` table + `SiteRegistry`
protocol + boot selection (the file becomes the `static`/local impl; a DB impl is
added). Reconcile the one difference: the north-star would key on a stable id with
hostname(s) as routing entries, rather than hostname-as-key — treat that as the
plan's open decision when it's picked up. Only build the DB version when tenants
change often or need an admin API; a file is the right weight for a handful of
rarely-changing tenants (YAGNI).

### 3. Keep the axes separate

`ownership.clj` already models two axes: `:owner-col` (user) and `:site-col`
(site/hostname). A third, the **workflow/account** axis (who owns Projects,
Workflows, runs M2M calls), is plausibly distinct again from the CMS *site*.
Do **not** fuse them under one `tenant_id` because they all smell like
"multi-tenancy" — that would manufacture the very complecting we're removing.
Establish whether they share an identity axis *before* unifying (hammock item).

### 4. Enforcement: extend the chokepoint, reject RLS

The required-argument scoped API already exists for the owner axis
(`ownership.clj`). The north-star direction is to bring the **legacy CMS
site-scoped reads** under the same pattern: a `scoped-find` (and friends) that
*require* the site value, with scoped tables reachable only through it, driven
off a table registry (extend `ownership.clj`'s `resource-specs`). Global/
tenant-agnostic tables (migrations, resolution bootstrap, cross-tenant admin,
the workflow/account axis) are reached through functions that simply don't take
a site — **not** through the scoped API with a `nil`/`:any` sentinel.

This gives "can't *accidentally* forget to scope" while keeping the value
explicit. It does **not** stop a deliberate raw-SQL bypass — only RLS does, and
RLS is rejected here (next section). For this codebase, accidental omission is
the real risk, so the chokepoint is the right weight.

### 5. Explicitly YAGNI

A tenant *management* control plane beyond the 2026-07-04 CRUD, schema-per-tenant
or DB-per-tenant isolation, custom-domain automation, and surrogate UUIDs for a
handful of rarely-changing tenants. Build when scale or the API surface sends the
bill.

---

## Rejected alternatives

- **Host-rewriting middleware (impersonation).** A gated middleware that rewrites
  the inbound `Host` header to the pinned tenant. Rejected: it is place-oriented
  (mutates a value in flight so scattered derivations coincidentally agree), adds
  a live code path whose safety depends on an env var being *absent* in prod, and
  leaves the underlying conflation intact. "Inert in prod" is an easy property,
  not a simple one.
- **Re-tenanting the DB branch** (`UPDATE <tables> SET hostname = 'kal-eph-…'`).
  Rejected: it is a **destructive mutation of identity** across ~10 tables, done
  out-of-band from migrations (silent drift when a new site-scoped table is
  added), in a second language from the app, and it is **load-bearing on the very
  identity/address conflation** we want to remove — it stops working the moment
  the design is fixed. Keep the *seeding* instinct (representative data is
  valuable); drop the re-tenanting.
- **Postgres Row-Level Security.** Rejected as the primary mechanism: RLS's only
  input channel is a session/connection GUC (`SET app.tenant_id`), i.e. ambient,
  mutable, connection-scoped state — spooky action at a distance, and a
  connection-pool footgun (a leased connection retaining a prior tenant leaks
  data — the mechanism meant to prevent leakage becomes a cause). It trades
  explicit-but-repeated dataflow for implicit-but-stateful. Repetition of an
  explicit value is not the complexity worth paying ambient state to remove. May
  be reconsidered as *defense-in-depth under* an explicit scope if data
  sensitivity ever justifies it.
- **Baking the asset bucket into tenant identity** (`{:id … :asset-bucket …}`).
  Rejected: where bytes live is a per-*deployment* fact, not a property of the
  tenant; embedding it re-complects identity with placement and forces an
  environment-specific registry. Placement is a per-env *binding* (north-star).

---

## Sequencing

1. **Phase 1 — resolution seam + assets.** Add `http_api/tenant`, the resolver
   boot instruction, `wrap-resolve-tenant`; convert the static path to
   `site-value`. Wire ephemeral (`fixed`, `KALEIDOSCOPE_TENANT`, drop
   `EPHEMERAL_HOST_*`). Closes the reported asset bug. Prod unchanged.
2. **Phase 2 — content.** Mechanical `get-host` → `site-value` swap across the
   ~40 handler sites, test per handler. Ephemeral content renders.
3. **Write-isolation decision** (A/B/C above) — likely (B) alongside Phase 1.
4. **Seeding** — confirm/curate `staging` data (parallel, independent).
5. **North-star** — only when multi-tenancy is the explicit task; revive the
   2026-07-04 registry, extend the ownership chokepoint, resolve the
   slug-vs-surrogate and axis-unification questions on the hammock first.

## Sharp edges / risks

- **Middleware ordering.** `wrap-resolve-tenant` must run before the static
  routes and every tenant-scoped handler; verify placement in the middleware
  stack.
- **`TENANT` must be a known value.** Validate in `deploy-app`; the `wedding`
  bucket mapping is not identity, and `fixed` scoping to an unknown string 404s
  everything silently.
- **Content needs data.** Assets fixed in Phase 1 will make the shell look
  healthy even if the branch has no rows — don't mistake "assets load" for "site
  works." Themes especially.
- **Uploads.** Safe by isolation — an ephemeral upload resolves to the pinned
  tenant's isolated `tenant-assets/<slug>/` store and the notifier is `none`, so
  it can neither write prod media nor trigger the prod resizer. (Resized
  derivatives won't be generated in ephemeral, which is fine for review.)
- **Two host mechanisms.** `virtual_hosting.clj` (regex host router, effectively
  a no-op catch-all today) still exists alongside the resolver. Leave it; note
  the overlap.

## Open questions

1. ~~Write isolation~~ **Resolved (2026-07-16):** isolate the pinned tenant's
   whole asset store in a seeded `kal-ephemeral/tenant-assets/<slug>/` prefix;
   reads+writes stay there; notifier disabled. Read-only/split-read-write
   rejected.
2. Does `staging` already contain renderable rows (esp. `themes`) for each
   pinnable tenant, or is a seed step required? (Plus: curate the no-PII sample
   assets under `test-resources/ephemeral-sample-assets/<tenant>/`.)
3. North-star only: stable slug vs surrogate `site_id` (reconciling with the
   2026-07-04 hostname-as-key decision).
4. North-star only: is the workflow/account axis the same as the CMS site axis?
   (Default assumption: no — keep separate.)

## Docs / ops to update (in the same change)

Per `CLAUDE.md` sharp edges #5 and #6:

- `docs/operations.md` — ephemeral env vars (`KALEIDOSCOPE_TENANT_RESOLVER_TYPE`,
  `KALEIDOSCOPE_TENANT`; removal of `EPHEMERAL_HOST_*`), the `TENANT` input, and
  the write-isolation behavior.
- `Taskfile.yml` — keep in sync with the `TENANT` input added to
  `scripts/ephemeral/*`.
- `README` / `env.local.example` — mention `KALEIDOSCOPE_TENANT_RESOLVER_TYPE`
  (default `host`) if other resolver types are documented there.

## Testing strategy

- **Resolver unit tests:** `host` returns the Host header verbatim (alias/wedding
  cases); `fixed` returns `KALEIDOSCOPE_TENANT` regardless of Host.
- **Static path:** with `fixed`, `/favicon.ico`, `/static/*`, `/media/*` resolve
  to the pinned tenant's adapter (embedded/in-memory fs); a shell route
  (`/assets/*`) still resolves to `kaleidoscope.client`.
- **Handler scoping (Phase 2):** each converted handler scopes by `site-value`;
  with `fixed`, requests under a fly.dev-style host still read the pinned
  tenant's rows. Prod-mode (`host`) tests assert unchanged behavior.
- **Deploy guardrail:** `deploy-app` rejects an unknown `TENANT`.
- Framework per `CLAUDE.md`: Kaocha + matcher-combinators, embedded H2/Postgres.
