# DB-backed site/tenant registry

Date: 2026-07-04

## Problem

Multi-tenancy is not, in practice, deeply hardcoded — the *tenant key* (a
hostname string, from the `Host` header) is already threaded generically
through most of the system. What's actually hardcoded is a handful of
hostname → config maps that live in source and require a code deploy to
change. Concretely, from reading the current code:

1. **`kaleidoscope.init.env/domains`** (`src/kaleidoscope/init/env.clj:33-38`)
   — a set of the 4 served domains. **Dead code** — grepped for every
   reference, it's read nowhere outside its own definition.
2. **`kaleidoscope-static-content-adapter-boot-instructions`**
   (`env.clj:200-238`) — the real hardcoding. For each of the `s3`,
   `in-memory`, and `local-filesystem` storage launchers, a literal
   `{hostname -> storage-adapter}` map lists all 7 known hosts (4 real
   domains + `kaleidoscope.pub`/`.client` shared buckets + a
   `caheriaguilar.and.andrewslai.com` alias), plus `.localhost` variants for
   local dev. Onboarding a new tenant means editing this map and redeploying.
   There's already a `TODO` on line 199 acknowledging the collision risk of
   exact-hostname matching.
3. **`kaleidoscope-authentication-boot-instructions`**
   (`env.clj:154-169`) — the `custom-authenticated-user` *test/dev* auth
   backend hardcodes `<hostname>:admin` roles per known domain. Dev-only
   fixture, not production logic.

Everything else that looks tenant-related is already generic:

- **Routing** (`http_api/virtual_hosting.clj`) matches on a regex, not a
  hostname list — `env.clj`'s `make-http-handler` wires a single catch-all
  `#".*"` app today; per-tenant behavior happens downstream, not in routing.
- **RBAC** (`api/authorization.clj`) builds role strings as
  `(str server-name ":writer"/":reader"/":admin")` dynamically from the
  request's Host header — no hostname list to maintain.
- **Static content lookup** (`http_api/http_utils.clj:28-38`) does
  `(get static-content-adapters (get-host request))` — the *lookup* is
  generic; only the *map's contents* (item 2 above) are hardcoded.
- **`themes` table** (`resources/migrations/20231021023645-add-themes.up.sql`)
  already stores a per-hostname, per-owner JSON config blob in the DB, CRUD'd
  over HTTP — this is existing precedent for "site config lives in the DB,"
  just scoped to per-user theme data rather than infra config.

There is also an **existing, narrower solution to a related problem**: Phase
3/4 of `plans/2026-07-03-ephemeral-fly-environment/PLAN.md` (already shipped
— see `env.clj:213-221`, `persistence/filesystem/s3_impl.clj`'s `:prefix`
support) added `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS`/`_BUCKET`/`_PREFIX` env
vars specifically so an arbitrary ephemeral `*.fly.dev` host can resolve to a
shared bucket + key prefix, without editing the hardcoded map. This plan
should preserve that mechanism as an additive override for short-lived,
unscripted environments — it solves a different problem (throwaway
environments that shouldn't leave DB rows behind) than onboarding a real,
persistent tenant.

## Non-goals

- **Routing** — already generic, nothing to change.
- **RBAC role-string model** — `<hostname>:writer/reader/admin` stays as
  bare strings keyed off the Host header. The prior ownership-consolidation
  plan (`plans/2026-07-02-multi-tenant-ownership-consolidation/PLAN.md`)
  explicitly identified role+site concatenation as accidental complexity and
  chose *not* to fix it there — this plan shouldn't reopen that decision.
  Hostname stays the join key across the system; no new `site_id` FK is
  introduced into auth.
- **`themes.hostname`** and any other per-user, per-site data (owner-scoped
  config) — unaffected. It already works, and it's a different concern (user
  data) from the tenant *registry* (infra config) this plan adds.
- **Legacy CMS hostname columns** (`articles.hostname`, etc.) — free-text,
  no FK, explicitly out of scope for legacy-CMS code per `CLAUDE.md` unless
  it's the explicit task.
- **The ephemeral-host env var mechanism** — kept as-is, layered on top of
  the DB registry rather than replaced (see below).

## Target design

### 1. New `sites` table — the tenant registry

```sql
CREATE TABLE sites (
  id             UUID NOT NULL PRIMARY KEY,
  hostname       VARCHAR NOT NULL UNIQUE,
  display_name   VARCHAR,
  storage_type   VARCHAR NOT NULL,   -- 's3' | 'local-filesystem' | 'in-memory' | 'none'
  storage_config JSONB,              -- e.g. {"bucket": "andrewslai.com"} or {"root": "andrewslai.com"}
  is_active      BOOLEAN NOT NULL DEFAULT true,
  created_at     TIMESTAMP,
  modified_at    TIMESTAMP
);
```

This is deliberately **not** the `themes` table — `themes` is per-owner
frontend theme data; `sites` is the infra-level registry of which hostnames
exist and how to serve their static content. Unrelated concerns that happen
to both key off hostname.

### 2. `SiteRegistry` protocol, following the existing pluggable-backend convention

Every other backend (DB, storage, auth) is a protocol with a real impl and a
test/mock impl, selected at boot by an env var (see `env.clj`'s
`*-boot-instructions` pattern). Add the same shape:

```clojure
(defprotocol SiteRegistry
  (all-sites [this])   ;; -> seq of {:hostname ... :storage-type ... :storage-config ...}
  (get-site [this hostname]))
```

- `persistence.sites.rdbms-impl` — queries the `sites` table.
- `persistence.sites.static-impl` — in-memory map, for tests and local dev
  without a seeded DB (mirrors `persistence/filesystem/in-memory-impl.clj`).

New boot instruction in `env.clj`, alongside the existing ones:

```clojure
(def kaleidoscope-site-registry-boot-instructions
  {:name      :kaleidoscope-site-registry
   :path      "KALEIDOSCOPE_SITE_REGISTRY_TYPE"
   :launchers {"rdbms"  (fn [env] (sites-rdbms/make-registry (:database-connection env)))
               "static" (fn [_env] (sites-static/make-registry default-sites))}
   :default   "rdbms"})
```

### 3. Rewire the static-content-adapter launchers to read from the registry

`kaleidoscope-static-content-adapter-boot-instructions` (`env.clj:200-238`)
keeps its per-storage-type branching (s3 client vs local-fs vs in-memory)
but builds the map from `(site-registry/all-sites registry)` instead of a
literal map. Each site row's `storage_type` selects which adapter
constructor to call; `storage_config` supplies its arguments. The
`KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS`/`_BUCKET`/`_PREFIX` env var overlay
(already shipped) is merged in **after**, unchanged — same `cond->` shape
that exists today, just merged onto a DB-sourced base map instead of a
literal one.

Adapters are built **once at boot**, not per-request — same as today. Site
config changes rarely (onboarding a new tenant is not a hot path), so the
registry is read once during `start-system!` and the resulting adapter map
is what actually serves requests. Adding a new tenant via the admin API
(below) requires a restart to take effect in v1; call this out as a known
limitation rather than building live cache invalidation now. Revisit only if
onboarding frequency makes a restart genuinely annoying.

### 4. Admin CRUD API — the actual point of moving this to a DB

Without this, "DB-backed" just means "one more place to seed manually,"
which isn't better than editing source. Add:

- `api/sites.clj` — `create-site!`, `update-site!`, `deactivate-site!`,
  `get-sites`.
- `http_api/sites.clj` — routes, gated by a new cross-tenant admin role
  (see open question below — today's RBAC is entirely site-scoped, and
  "who may register a new site" isn't a question any existing site's
  `:admin` role can answer).

## Migration & rollout sequencing

1. **Schema migration**: create `sites` table.
2. **Seed migration** (`resources/db-seed/`, same convention as the existing
   articles/albums seed files): insert the current 7 hardcoded hostnames
   with their existing storage type/config, so behavior is byte-for-byte
   unchanged the moment this ships.
3. **Build `SiteRegistry` protocol + both impls**, wire the boot instruction
   into `DEFAULT-BOOT-INSTRUCTIONS`, but **don't switch the static-content
   launchers over yet** — land the registry and its tests independently.
4. **Rewire `kaleidoscope-static-content-adapter-boot-instructions`** to
   source from the registry. Verify in a real environment (or the ephemeral
   Fly environment from the other plan) that every existing hostname still
   resolves identically before relying on it in prod.
5. **Add the admin CRUD API** (`api/sites.clj` / `http_api/sites.clj`),
   decide and implement the cross-tenant admin role question below.
6. **Delete dead code**: the unused `domains` set, and the literal
   hostname→adapter maps in the three launchers, once step 4 is verified in
   production.
7. **(Optional, future, separate)**: add a `site_id` FK from `themes` (and
   any future site-scoped table) to `sites.id`, for referential integrity.
   Not required for this plan — hostname string stays the join key
   everywhere, consistent with the RBAC model's existing string-based
   convention (see Non-goals).

## Tests

- New: `persistence/sites` tests against embedded-h2/embedded-postgres for
  the rdbms impl (matching the existing pattern for other rdbms-backed
  persistence namespaces).
- New: a unit test on the rewired
  `kaleidoscope-static-content-adapter-boot-instructions` confirming it
  builds the same adapter map from a seeded static registry as it did from
  the old literal map, plus confirms the ephemeral-host env var overlay
  still merges correctly on top.
- Update: `test/kaleidoscope/init/env_test.clj` and any other test that
  currently asserts against the literal hardcoded map — point them at the
  `static` `SiteRegistry` impl seeded with the same fixture hostnames
  instead.
- New: an admin-CRUD test — creating a site via the API and confirming a
  subsequent boot (or explicit reload, if built) serves its static content —
  covers the actual feature this plan exists to deliver.

## Open questions to resolve before implementation

1. **Cross-tenant admin role.** Every existing RBAC check is scoped to one
   site (`<hostname>:admin`). Registering a *new* site isn't an action any
   existing site's admin role can authorize. Needs a decision: a new global
   role (e.g. `platform:admin`), or restrict site CRUD to a
   non-HTTP/operator-only path (migration/script) for now and defer the API
   until there's a real second admin who needs it.
2. **Live reload vs. restart-on-change.** Proposed default is "rebuild the
   adapter map at boot, restart to pick up a new site." Confirm that's
   acceptable given expected onboarding frequency, or scope in a cache-bust
   mechanism now.
3. **`storage_config` shape validation.** Recommend a Malli schema keyed by
   `storage_type` (e.g. `{:bucket string?}` for `s3`, `{:root string?}` for
   `local-filesystem`) enforced in `api/sites.clj` at creation time, so a
   malformed row can't silently break static-content resolution for a whole
   tenant.
