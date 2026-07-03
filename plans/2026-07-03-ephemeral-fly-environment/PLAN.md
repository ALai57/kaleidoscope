# Ephemeral Fly.io Test Environment for Kaleidoscope

## Context

Kaleidoscope currently has no staging/ephemeral environment — only local dev (embedded H2, mocked auth/LLM) and production (`kaleidoscope-publishing` on Fly.io, real Neon/Auth0/Anthropic/Bugsnag/OTEL). The goal is a way to quickly stand up a short-lived, realistic environment on Fly.io that exercises every real dependency end-to-end (Auth0, Neon Postgres, Anthropic API, Bugsnag, OTEL), so a developer can validate a change against production-like infrastructure before merging — without waiting on a fully separate persistent staging stack.

Decisions already made with the user:
1. **Lifecycle**: build an on-demand CLI workflow first (`task env:up` / `task env:down`); GitHub Actions automation is an explicit future phase, not built now.
2. **Fidelity**: fully real dependencies — real Auth0, real Neon, real Anthropic API, real Bugsnag, real OTEL pipeline. No mocks.
3. **Scope**: backend + frontend. The frontend (`kaleidoscope-ui`, checked out locally at `/Users/alai/code/kaleidoscope-ui`) gets built and served too, not just the API.
4. **Database**: one shared "staging" Neon database, with each ephemeral environment isolated via its own Postgres **schema** inside that database (not a new Neon branch/project per environment).

Two small, additive code changes are required because today the app has no concept of a non-`public` schema and no way to serve static assets under an arbitrary `*.fly.dev` hostname. Everything else (Auth0, OTEL, Bugsnag, Anthropic) is reachable purely through existing env-var-driven boot instructions in `kaleidoscope.init.env` — no code changes needed there.

---

## What gets built (Phase A — CLI now)

### New scripts: `scripts/ephemeral/`

A new directory of composable scripts, following the exact `getopts ":-:"` long-flag convention already used by `bin/run`, `bin/db-migrate`, `bin/psql-connect`:

- `scripts/ephemeral/lib.sh` — shared helpers: derive a deterministic **slug** from `--name=` or the current git branch (lowercased, non-alphanumerics→`-`, truncated to 20 chars). All resource names derive from this one slug so `up`/`down` always agree on what they own:
  - Fly app: `kal-eph-<slug>`
  - Postgres schema: `kal_eph_<slug_with_underscores>`
  - S3 bucket: `kal-eph-<slug>`
  - `OTEL_SERVICE_NAME`: `kal-eph-<slug>` (gives full trace/log separation from prod in Tempo/Grafana with zero collector changes)
- `scripts/ephemeral/provision-db` — `CREATE SCHEMA IF NOT EXISTS`, then runs migrations scoped to it (`KALEIDOSCOPE_DB_SCHEMA=<schema> clojure -A:dev -M -m kaleidoscope.persistence.rdbms.migrations migrate`, same invocation `bin/db-migrate` uses today, plus the new env var).
- `scripts/ephemeral/build-frontend` — `cd kaleidoscope-ui && npm run build-release` (→ `resources/kaleidoscope.pub/static/*`, same output `kaleidoscope-ui/scripts/deployment/deploy-kaleidoscope-pub` already syncs from), then `aws s3 mb` + `aws s3 sync` into a fresh per-environment bucket.
- `scripts/ephemeral/deploy-app` — generates a per-run `fly.toml` from the root one as a template (app name, `OTEL_SERVICE_NAME`, and the new env vars overridden; scale-to-zero VM settings — see below), `fly apps create`, `fly secrets set` for true secrets, `fly deploy -c <generated-toml>`.
- `scripts/ephemeral/smoke-test` — `curl /ping`, curl `/` to confirm the static-content alias resolved (a miss 404s per `http_utils.clj`), optionally an Auth0 M2M token + one authenticated write to validate DB+Auth0+authz together.
- `scripts/ephemeral/up` / `scripts/ephemeral/down` — orchestrate the above in order; `down` runs `fly apps destroy`, `DROP SCHEMA ... CASCADE`, `aws s3 rb --force`.

Taskfile additions:
```yaml
env:up:
  desc: Provision an ephemeral Fly.io test environment (backend + frontend)
  cmd: ./scripts/ephemeral/up --name={{.NAME}}

env:down:
  desc: Tear down an ephemeral Fly.io test environment
  cmd: ./scripts/ephemeral/down --name={{.NAME}}
```

### Code change 1 — Postgres schema support (currently missing entirely)

Neither `env->pg-conn` nor Migratus's config passes a schema today — the app always uses the connection role's default (`public`). Add an optional `KALEIDOSCOPE_DB_SCHEMA` env var, no-op when unset (prod is unaffected):

- `src/kaleidoscope/init/env.clj` — `env->pg-conn`: add `:currentSchema` (pgJDBC connection property that sets `search_path`) when `KALEIDOSCOPE_DB_SCHEMA` is set.
- `src/kaleidoscope/persistence/rdbms/live_pg.clj` — same `:currentSchema` addition (used by the migrations entrypoint).
- `src/kaleidoscope/persistence/rdbms/migrations.clj` — pass `:schema` to the Migratus config map, and add a small `ensure-schema!` fn that runs `CREATE SCHEMA IF NOT EXISTS` before migrating (Migratus won't create a missing schema on its own).

### Code change 2 — static-content host aliasing (currently hardcoded)

`kaleidoscope-static-content-adapter-boot-instructions` in `env.clj` is an exact-hostname-keyed map (`andrewslai.com`, `kaleidoscope.pub`, …) — an unrecognized `*.fly.dev` host resolves to `nil` and 404s on every static asset. API/CRUD routes are unaffected (routing is already a catch-all regex). Add two optional env vars, `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS` and `KALEIDOSCOPE_EPHEMERAL_HOST_BUCKET`, merged into the `"s3"` adapter map when set — same pattern as the existing `"andrewslai.com.localhost"` alias already in that function, just env-driven instead of hardcoded. No changes needed to `virtual_hosting.clj` (catch-all routing) or `authorization.clj` (role strings are already derived dynamically from the Host header).

### Third-party configuration needed

| System | What to configure | Notes |
|---|---|---|
| **Neon** | Provision a new `staging` database in the same Neon project as prod (Neon console or `neonctl databases create --name staging`) | One-time, out-of-band prerequisite — not scripted. Schema isolation happens *within* this database per the user's decision; keeping it a separate `pg_database` from prod's `neondb` keeps ephemeral test data structurally apart from production data. |
| **Auth0** | Reuse the existing dev tenant/app (`dev-722l4eivlaenj2h1.us.auth0.com`, audience `https://api.andrewslai.com`) — no new tenant. Verify whether **Allowed Callback URLs / Web Origins / Logout URLs** accept a wildcard (`https://*.fly.dev/*`) for the browser-login case. | Automated/CLI smoke testing needs **no Auth0 change at all** — it can reuse the M2M client-credentials flow already proven in production (Checkly's canary uses it today), since `require-*-writer` admits `:service-account` identities. Only a human clicking through the actual frontend UI needs the callback URL registered. |
| **AWS** | Scope a set of credentials (or reuse an existing profile) with `s3:CreateBucket`/`PutObject`/`DeleteObject`/`DeleteBucket` for the ephemeral bucket-per-env pattern | Stored in a new untracked `.env.fly.staging` file (same shape as existing `.env.fly.prod`, already covered by the `*.env*` gitignore rule). |
| **Anthropic** | Reuse the existing `ANTHROPIC_API_KEY` | Set as a Fly secret on each ephemeral app. See cost note below. |
| **Bugsnag** | Reuse the existing API key | Pre-existing gap, not part of this plan: `BugsnagClient` never sets `releaseStage`, so ephemeral-env errors will show up tagged as `production` in the Bugsnag dashboard, indistinguishable from real prod errors. Worth a follow-up ticket. |
| **OTEL / Grafana Tempo / Sumo Logic** | No configuration needed | `kaleidoscope-otel-collector` is reached via Fly's `.internal` 6PN network automatically for any app in the same org — zero extra config. Traces are separated from prod purely by the per-environment `OTEL_SERVICE_NAME`. **Sumo log shipping is explicitly out of scope** (the log shipper filters by `APP_NAME=kaleidoscope-publishing`) — use `fly logs -a <ephemeral-app>` directly instead. |
| **`.env.fly.staging`** (new, local, untracked) | `KALEIDOSCOPE_DB_HOST/_NAME/_USER/_PASSWORD/_PORT/_SSL_MODE` pointed at the new Neon `staging` database, plus AWS credentials | One-time manual setup by the developer; `env:up`/`env:down` source it for anything that can't be read back from Fly secrets (Fly secrets are write-only). |

### Fly VM sizing (cost lever)

Prod uses `auto_stop_machines = false`, `min_machines_running = 1` — always-on. The generated ephemeral `fly.toml` should instead scale to zero: `auto_stop_machines = true`, `auto_start_machines = true`, `min_machines_running = 0`, same `1gb` shared-cpu-1x VM (the Dockerfile hardcodes `-Xmx512m`, so shrinking further risks OOM). This means idle ephemeral environments cost essentially nothing between test runs, at the cost of a cold-start on the first request after idling.

---

## Cost estimates (rough — verify current published pricing before relying on these)

Assuming **intermittent developer usage** — a handful of hours of actual running time per month, plus occasional smoke-test workflow runs:

- **Fly.io compute**: shared-cpu-1x + 1GB, scale-to-zero. Fly bills per-second only while a machine is running. Full-time (730 hrs/mo) this VM class costs roughly **$3–4/mo**; with scale-to-zero and only a few hours of actual test runtime per month, expect well under **$1/mo**.
- **Neon**: schema-per-environment inside one shared `staging` database has near-zero marginal cost over what prod already pays — storage for a few extra schemas is negligible, and compute is billed by usage regardless of schema count. If prod is already on a paid Neon plan, expect **$0–low single digits/mo** in marginal cost.
- **Auth0**: **$0** — reuses the existing dev tenant; M2M client-credentials calls don't consume the Auth0 free tier's MAU allotment the way interactive logins do.
- **AWS S3**: a few MB of JS bundles per environment, deleted on teardown — **effectively $0** (well under a cent per environment-month).
- **Anthropic API**: the only cost that scales with *how much you test*, not with how long the environment exists. This app uses `claude-opus-4-6` ($5/$25 per million input/output tokens, no prompt caching). A single full `autonomous-team-review` workflow run (PM review + Eng review + Judge + Task generation, ~4-6 LLM calls) is roughly 15–30K input + 5–10K output tokens, i.e. **~$0.15–$0.35 per full workflow smoke test**. A lighter single-agent scoring call is a few cents. Running this in a tight loop (e.g. an automated per-commit CI job in Phase B) is the one place cost could add up — worth gating behind a manual trigger or a `--mock-ai` flag on `env:up` for pure infra/plumbing test runs.

**Bottom line**: for on-demand, manually-triggered testing, total infra cost should be well under **$5/mo**, dominated by however many real Anthropic-backed workflow runs you choose to smoke-test.

---

## Explicitly out of scope (this phase)

- GitHub Actions / per-PR automation (Phase B — sketched below, not built).
- A separate Auth0 tenant.
- Sumo Logic log shipping for ephemeral apps (`fly logs -a <app>` instead).
- Automatic `kaleidoscope-ui` ref pinning — `env:up` builds whatever is currently checked out locally.
- Bugsnag `releaseStage` tagging fix (pre-existing gap, flagged for a follow-up ticket).

## Phase B (future, not built now)

Extend `.github/workflows/clojure.yml` with a PR-triggered job that calls the *same* `scripts/ephemeral/up --name=pr-<N>` / `down` scripts built in Phase A (the point of building CLI-first), with secrets mirrored into GitHub Actions, a teardown trigger on PR close, and a PR comment posting the ephemeral URL. Real-LLM steps should be gated behind a label or `workflow_dispatch` rather than running on every push, given the Anthropic cost note above.

---

## Verification

1. `task env:up` from a feature branch → confirm a new Fly app, schema, and S3 bucket are created with matching names; `smoke-test` step passes (`/ping` 200, `/` serves the built frontend, one authenticated write succeeds via Auth0 M2M).
2. Manually browse to the printed `https://kal-eph-<slug>.fly.dev` URL, confirm the frontend loads and an interactive Auth0 login works (validates whichever callback-URL approach was chosen).
3. Confirm traces for the ephemeral run are visible in Grafana Tempo tagged with the per-environment `OTEL_SERVICE_NAME`, separate from `kaleidoscope-publishing`.
4. `task env:down` → confirm the Fly app, Postgres schema, and S3 bucket are all gone (`fly apps list`, `psql` schema check, `aws s3 ls`).
5. Run `task test` unaffected — confirm the new `KALEIDOSCOPE_DB_SCHEMA` env var is a no-op when unset, so existing embedded-H2/embedded-Postgres test suites and local dev are unchanged.
