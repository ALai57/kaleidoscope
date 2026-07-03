# Ephemeral Fly.io Test Environment for Kaleidoscope

## Context

Kaleidoscope currently has no staging/ephemeral environment — only local dev (embedded H2, mocked auth/LLM) and production (`kaleidoscope-publishing` on Fly.io, real Neon/Auth0/Anthropic/Bugsnag/OTEL). The goal is a way to quickly stand up a short-lived, realistic environment on Fly.io that exercises every real dependency end-to-end (Auth0, Neon Postgres, Anthropic API, Bugsnag, OTEL), so a developer can validate a change against production-like infrastructure before merging — without waiting on a fully separate persistent staging stack.

Decisions already made with the user:
1. **Lifecycle**: build an on-demand CLI workflow first (`task env:up` / `task env:down`); GitHub Actions automation is an explicit future phase, not built now.
2. **Fidelity**: fully real dependencies — real Auth0, real Neon, real Anthropic API, real Bugsnag, real OTEL pipeline. No mocks.
3. **Scope**: backend + frontend. The frontend (`kaleidoscope-ui`, checked out locally at `/Users/alai/code/kaleidoscope-ui`) gets built and served too, not just the API.
4. **Database**: each ephemeral environment gets its own **Neon branch** — instant, copy-on-write, forked from a dedicated long-lived `staging` branch (never from `main`/prod) — rather than a schema inside a shared database.
5. **Static assets**: one persistent S3 bucket (`kal-ephemeral`), with each ephemeral environment isolated via its own **key prefix** inside that bucket — not a bucket per environment.

Two small, additive code changes are required: the app has no way to serve static assets under an arbitrary `*.fly.dev` hostname, and the S3 adapter has no way to scope itself to a subpath so multiple ephemeral environments can share one bucket. Everything else — Auth0, OTEL, Bugsnag, Anthropic, and the database — is reachable purely through existing env-var-driven boot instructions in `kaleidoscope.init.env`; a Neon branch is a normal standalone Postgres endpoint with its own connection string, so the app talks to it exactly like it talks to local or prod Postgres today, no schema-awareness code needed.

---

## What gets built (Phase A — CLI now)

### New scripts: `scripts/ephemeral/`

A new directory of composable scripts, following the exact `getopts ":-:"` long-flag convention already used by `bin/run`, `bin/db-migrate`, `bin/psql-connect`:

- `scripts/ephemeral/lib.sh` — shared helpers: derive a deterministic **slug** from `--name=` or the current git branch (lowercased, non-alphanumerics→`-`, truncated to 20 chars). All resource names derive from this one slug so `up`/`down` always agree on what they own:
  - Fly app: `kal-eph-<slug>`
  - Neon branch: `eph-<slug>`
  - S3 key prefix: `eph-<slug>/` inside the one persistent `kal-ephemeral` bucket
  - `OTEL_SERVICE_NAME`: `kal-eph-<slug>` (gives full trace/log separation from prod in Tempo/Grafana with zero collector changes)
- `scripts/ephemeral/provision-db` — `neonctl branches create --parent staging --name eph-<slug>`, then reads back the branch's connection string via `neonctl connection-string eph-<slug>` and runs migrations against it with the exact same invocation `bin/db-migrate` uses today — no new env var, no schema config, nothing to change in the app. Because the branch is a copy-on-write fork of `staging`, it already has whatever schema and data `staging` had at fork time; running migrations here just catches anything not yet applied to `staging` and validates it against realistic data before it reaches prod.
- `scripts/ephemeral/build-frontend` — `cd kaleidoscope-ui && npm run build-release` (→ `resources/kaleidoscope.pub/static/*`, same output `kaleidoscope-ui/scripts/deployment/deploy-kaleidoscope-pub` already syncs from), then `aws s3 sync ./build s3://kal-ephemeral/eph-<slug>/` — no bucket create, just a sync into that environment's prefix in the one shared bucket.
- `scripts/ephemeral/deploy-app` — generates a per-run `fly.toml` from the root one as a template (app name, `OTEL_SERVICE_NAME`, the Neon branch's connection string, and the new host-alias/prefix env vars overridden; scale-to-zero VM settings — see below), `fly apps create`, `fly secrets set` for true secrets, `fly deploy -c <generated-toml>`.
- `scripts/ephemeral/smoke-test` — `curl /ping`, curl `/` to confirm the static-content alias resolved (a miss 404s per `http_utils.clj`), optionally an Auth0 M2M token + one authenticated write to validate DB+Auth0+authz together.
- `scripts/ephemeral/up` / `scripts/ephemeral/down` — orchestrate the above in order; `down` runs `fly apps destroy`, `neonctl branches delete eph-<slug>`, `aws s3 rm s3://kal-ephemeral/eph-<slug>/ --recursive`.

Taskfile additions:
```yaml
env:up:
  desc: Provision an ephemeral Fly.io test environment (backend + frontend)
  cmd: ./scripts/ephemeral/up --name={{.NAME}}

env:down:
  desc: Tear down an ephemeral Fly.io test environment
  cmd: ./scripts/ephemeral/down --name={{.NAME}}
```

### Code change 1 — static-content host aliasing (currently hardcoded)

`kaleidoscope-static-content-adapter-boot-instructions` in `env.clj` is an exact-hostname-keyed map (`andrewslai.com`, `kaleidoscope.pub`, …) — an unrecognized `*.fly.dev` host resolves to `nil` and 404s on every static asset. API/CRUD routes are unaffected (routing is already a catch-all regex). Add optional env vars, `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS` and `KALEIDOSCOPE_EPHEMERAL_HOST_BUCKET`, merged into the `"s3"` adapter map when set — same pattern as the existing `"andrewslai.com.localhost"` alias already in that function, just env-driven instead of hardcoded. No changes needed to `virtual_hosting.clj` (catch-all routing) or `authorization.clj` (role strings are already derived dynamically from the Host header).

### Code change 2 — S3 key-prefix support (currently missing)

`make-s3` (`persistence/filesystem/s3_impl.clj`) only accepts `{:bucket, :region}` — there's no way to scope one adapter instance to a subpath within a bucket, so today the only way to isolate environments is one bucket per environment. Add an optional `:prefix` key: `get-file`/`put-file` prepend it to the S3 `Key` before every `GetObject`/`PutObject` call, no-op when unset (prod's bucket-per-host config, which never sets `:prefix`, is unaffected). Thread a new `KALEIDOSCOPE_EPHEMERAL_HOST_PREFIX` env var through `kaleidoscope-static-content-adapter-boot-instructions` alongside the existing bucket/alias vars for the ephemeral host.

### Third-party configuration needed

| System | What to configure | Notes |
|---|---|---|
| **Neon** | Create one persistent `staging` branch, forked once from `main`, in the same Neon project as prod (Neon console or `neonctl branches create --name staging --parent main`) | One-time, out-of-band prerequisite — not scripted. Every ephemeral branch forks from `staging`, never from `main`, so ephemeral test data is structurally isolated from live production data and no ephemeral run can write back to prod. |
| **Auth0** | Reuse the existing dev tenant/app (`dev-722l4eivlaenj2h1.us.auth0.com`, audience `https://api.andrewslai.com`) — no new tenant. Verify whether **Allowed Callback URLs / Web Origins / Logout URLs** accept a wildcard (`https://*.fly.dev/*`) for the browser-login case. | Automated/CLI smoke testing needs **no Auth0 change at all** — it can reuse the M2M client-credentials flow already proven in production (Checkly's canary uses it today), since `require-*-writer` admits `:service-account` identities. Only a human clicking through the actual frontend UI needs the callback URL registered. |
| **AWS** | Create one persistent `kal-ephemeral` S3 bucket, once, in the same AWS account as prod (console or `aws s3 mb s3://kal-ephemeral`); scope credentials to `s3:PutObject`/`GetObject`/`DeleteObject` on that bucket only — no `CreateBucket`/`DeleteBucket` needed since environments isolate via key prefix, not bucket lifecycle | One-time, out-of-band prerequisite — not scripted, same shape as the Neon `staging` branch setup. Credentials stored in a new untracked `.env.fly.staging` file (same shape as existing `.env.fly.prod`, already covered by the `*.env*` gitignore rule). |
| **Anthropic** | Reuse the existing `ANTHROPIC_API_KEY` | Set as a Fly secret on each ephemeral app. See cost note below. |
| **Bugsnag** | Reuse the existing API key | Pre-existing gap, not part of this plan: `BugsnagClient` never sets `releaseStage`, so ephemeral-env errors will show up tagged as `production` in the Bugsnag dashboard, indistinguishable from real prod errors. Worth a follow-up ticket. |
| **OTEL / Grafana Tempo / Sumo Logic** | No configuration needed | `kaleidoscope-otel-collector` is reached via Fly's `.internal` 6PN network automatically for any app in the same org — zero extra config. Traces are separated from prod purely by the per-environment `OTEL_SERVICE_NAME`. **Sumo log shipping is explicitly out of scope** (the log shipper filters by `APP_NAME=kaleidoscope-publishing`) — use `fly logs -a <ephemeral-app>` directly instead. |
| **`.env.fly.staging`** (new, local, untracked) | `NEON_API_KEY` + `NEON_PROJECT_ID` (used by `neonctl` to create/delete branches and fetch each branch's connection string at `up`/`down` time), plus AWS credentials | One-time manual setup by the developer. Unlike a schema-based design, no static `KALEIDOSCOPE_DB_HOST/_NAME/_USER/_PASSWORD` lives here — each `env:up` fetches a fresh connection string for that run's branch via `neonctl connection-string eph-<slug>`. |

### Fly VM sizing (cost lever)

Prod uses `auto_stop_machines = false`, `min_machines_running = 1` — always-on. The generated ephemeral `fly.toml` should instead scale to zero: `auto_stop_machines = true`, `auto_start_machines = true`, `min_machines_running = 0`, same `1gb` shared-cpu-1x VM (the Dockerfile hardcodes `-Xmx512m`, so shrinking further risks OOM). This means idle ephemeral environments cost essentially nothing between test runs, at the cost of a cold-start on the first request after idling.

---

## Cost estimates (rough — verify current published pricing before relying on these)

Assuming **intermittent developer usage** — a handful of hours of actual running time per month, plus occasional smoke-test workflow runs:

- **Fly.io compute**: shared-cpu-1x + 1GB, scale-to-zero. Fly bills per-second only while a machine is running. Full-time (730 hrs/mo) this VM class costs roughly **$3–4/mo**; with scale-to-zero and only a few hours of actual test runtime per month, expect well under **$1/mo**.
- **Neon**: branches are copy-on-write — a branch only pays for pages that diverge from `staging`, so storage cost for a short-lived, lightly-mutated branch is negligible. Each branch gets its own compute endpoint, but on autosuspend it costs ~$0 while idle. Expect **$0–low single digits/mo** in marginal cost, same order of magnitude as a schema-based design, without the shared-instance contention risk of multiple environments hitting one Postgres process.
- **Auth0**: **$0** — reuses the existing dev tenant; M2M client-credentials calls don't consume the Auth0 free tier's MAU allotment the way interactive logins do.
- **AWS S3**: a few MB of JS bundles per environment, stored as a key prefix in the one shared `kal-ephemeral` bucket and deleted on teardown — **effectively $0** (well under a cent per environment-month). Sharing one bucket also avoids per-env `CreateBucket`/`DeleteBucket` calls entirely, which were the slowest and most rate-limit-prone part of a bucket-per-environment design.
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

1. `task env:up` from a feature branch → confirm a new Fly app, Neon branch, and S3 key prefix (`eph-<slug>/` in `kal-ephemeral`) are created with matching names; `smoke-test` step passes (`/ping` 200, `/` serves the built frontend, one authenticated write succeeds via Auth0 M2M).
2. Manually browse to the printed `https://kal-eph-<slug>.fly.dev` URL, confirm the frontend loads and an interactive Auth0 login works (validates whichever callback-URL approach was chosen).
3. Confirm traces for the ephemeral run are visible in Grafana Tempo tagged with the per-environment `OTEL_SERVICE_NAME`, separate from `kaleidoscope-publishing`.
4. `task env:down` → confirm the Fly app and Neon branch are gone (`fly apps list`, `neonctl branches list` no longer shows `eph-<slug>`) and the S3 prefix is empty (`aws s3 ls s3://kal-ephemeral/eph-<slug>/` returns nothing), while the shared bucket itself still exists.
5. Run `task test` unaffected — no DB-related code changed, and the new `make-s3` `:prefix` option is a no-op when unset, so existing embedded-H2/embedded-Postgres test suites, local dev, and prod's bucket-per-host config are all untouched by construction.
