# Ephemeral Fly.io Test Environment for Kaleidoscope

## Context

Kaleidoscope currently has no staging/ephemeral environment — only local dev (embedded H2, mocked auth/LLM/AI) and production (`kaleidoscope-publishing` on Fly.io, real Neon/Auth0/Bugsnag/OTEL; AI features mocked even in prod today). The goal is a way to quickly stand up a short-lived, realistic environment on Fly.io that exercises every real dependency prod actually has end-to-end (Auth0, Neon Postgres, Bugsnag, OTEL), so a developer can validate a change against production-like infrastructure before merging — without waiting on a fully separate persistent staging stack.

Decisions already made with the user:
1. **Lifecycle**: build an on-demand CLI workflow first (`task env:up` / `task env:down`); GitHub Actions automation is an explicit future phase, not built now.
2. **Fidelity**: fully real dependencies — real Auth0, real Neon, real Bugsnag, real OTEL pipeline. No mocks. **Exception**: prod itself runs the AI workflow/scoring features mocked today (`fly.toml` never sets `KALEIDOSCOPE_SCORER_TYPE`/`KALEIDOSCOPE_WORKFLOW_EXECUTOR_TYPE` to `"llm"`, no `ANTHROPIC_API_KEY` Fly secret exists) — ephemeral environments match that, not a hypothetical "real prod." A real Anthropic key can be added later as an opt-in for whoever specifically wants to smoke-test the AI features (see cost note below).
3. **Scope**: backend + frontend. The frontend (`kaleidoscope-ui`, checked out locally at `/Users/alai/code/kaleidoscope-ui`) gets built and served too, not just the API.
4. **Database**: each ephemeral environment gets its own **Neon branch** — instant, copy-on-write, forked from a dedicated long-lived `staging` branch (never from `main`/prod) — rather than a schema inside a shared database.
5. **Static assets**: one persistent S3 bucket (`kal-ephemeral`), with each ephemeral environment isolated via its own **key prefix** inside that bucket — not a bucket per environment.
6. **Authorization**: every `*.fly.dev` host shares one Auth0 role namespace (`ephemeral:writer`/`:reader`/`:admin`) instead of either bypassing RBAC (`public-access`) or minting a new per-slug Auth0 role on every `env:up`. `api/authorization.clj`'s `require-*-writer`/`-reader`/`-admin` normalize any `*.fly.dev` server-name to the fixed `"ephemeral"` role-domain before building the role string — data-scoping (e.g. `articles.hostname`) is untouched, since it's derived independently from the same Host header via `http-utils/get-host`, not from this role check. Real hostnames never match the `*.fly.dev` suffix, so prod RBAC is unaffected. **Already implemented** (`api/authorization.clj`, `role-domain`/`ephemeral-host?`), landed ahead of Phase 1 since Phase 1's Auth0 M2M write check depends on it.

The work is broken into six phases below, each independently deployable and verifiable on its own. Phase 2 already delivers the core ask — one command that deploys the existing, unmodified application to a new ephemeral environment against real dependencies. Phases 3–5 add real frontend serving to reach full scope (decision #3).

---

## Phases

### Phase 1 — Manual validation spike (no code, no scripts)

**Goal**: de-risk the core assumption — that an unmodified deploy of this app, pointed at a brand-new Neon branch and a brand-new Fly app, actually works end-to-end against every real dependency it currently has in prod (Auth0, Bugsnag, OTEL/Tempo, Sumo Logic — Anthropic excluded per decision #2, since prod itself runs AI features mocked) — before investing in any automation.

**Do by hand**:
1. One-time: `neonctl branches create --project-id <proj> --name staging --parent main` (see Third-party configuration below).
2. One-time: in the Auth0 dashboard, create an `ephemeral:writer` role (and `ephemeral:admin` if needed) and assign it to the M2M test client used for smoke testing (see Third-party configuration below). This is the only Auth0-side setup ever needed for ephemeral environments — decision #6 means it's never repeated per slug.
3. `neonctl branches create --project-id <proj> --name eph-spike --parent staging`.
4. Copy `fly.toml` → a scratch `fly.eph-spike.toml`, changing only the app name (`kal-eph-spike`) and `OTEL_SERVICE_NAME`.
5. `fly apps create kal-eph-spike`; `fly secrets set` with the branch's connection string (`neonctl connection-string eph-spike`) plus the existing Auth0 and Bugsnag secrets. (No `ANTHROPIC_API_KEY` — prod itself runs the AI features mocked today, so this spike matches that rather than introducing a new real dependency.)
6. `fly deploy -c fly.eph-spike.toml` — deploys the exact code that would be built from the current branch today (the `role-domain` authorization change from decision #6 is already merged, but is a no-op for every real hostname).

**Verify**:
- `curl https://kal-eph-spike.fly.dev/ping` → 200.
- **Auth0**: one authenticated write via an Auth0 M2M client-credentials token succeeds (proves Auth0 + the new Neon branch + authz all work together). Interactive browser login is out of scope here — it needs the frontend (Phase 5) and the wildcard callback-URL check in Third-party configuration.
- **OTEL / Grafana Tempo**: the request's trace shows up in Tempo tagged with `OTEL_SERVICE_NAME=kal-eph-spike`, separate from `kaleidoscope-publishing`.
- **Bugsnag**: trigger a deliberate server error (e.g. hit a route that throws) and confirm it appears in the Bugsnag dashboard. It will show up tagged `production` — `BugsnagClient` never sets `releaseStage` (see the pre-existing gap noted below) — but this at least confirms the API key and wiring actually work end-to-end before any script relies on it.
- **Sumo Logic**: confirm `fly logs -a kal-eph-spike` shows real-time logs locally, and confirm nothing shows up in Sumo Logic (its shipper filters by `APP_NAME=kaleidoscope-publishing`). This validates `fly logs` is genuinely the only way to see ephemeral logs, decided explicitly now rather than assumed later.
- `/` 404s — expected and fine for this phase, since no static-content code changes exist yet.

**Teardown**: `fly apps destroy kal-eph-spike`, `neonctl branches delete eph-spike`.

Nothing here is checked into the repo — it's a throwaway dry run whose only output is confidence that Phase 2's automation has no surprises waiting.

---

### Phase 2 — Scripted backend-only `env:up` / `env:down`

**Goal**: turn Phase 1's manual recipe into one command. **This is the milestone that satisfies "manually run a command to deploy the existing code to a new ephemeral environment"** — backend only; frontend arrives in Phase 5.

**Builds**:
- `scripts/ephemeral/lib.sh` — shared helpers: derive a deterministic **slug** from `--name=` or the current git branch (lowercased, non-alphanumerics→`-`, truncated to 20 chars). For this phase, only two resource names derive from it:
  - Fly app: `kal-eph-<slug>`
  - Neon branch: `eph-<slug>`
- `scripts/ephemeral/provision-db` — `neonctl branches create --parent staging --name eph-<slug>`, reads back the connection string via `neonctl connection-string eph-<slug>`, then runs migrations against it with the exact same invocation `bin/db-migrate` uses today. No new env var, no schema config.
- `scripts/ephemeral/deploy-app` — generates a per-run `fly.toml` from the root one as a template: app name, `OTEL_SERVICE_NAME`, the Neon branch's connection string as secrets, and scale-to-zero VM settings (`auto_stop_machines = true`, `auto_start_machines = true`, `min_machines_running = 0`, same `1gb` shared-cpu-1x VM — the Dockerfile hardcodes `-Xmx512m`, so shrinking further risks OOM). Then `fly apps create`, `fly secrets set` for true secrets, `fly deploy -c <generated-toml>`.
- `scripts/ephemeral/smoke-test` — `curl /ping`, plus an Auth0 M2M token + one authenticated write to validate DB+Auth0+authz together. No frontend check yet.
- `scripts/ephemeral/up` / `scripts/ephemeral/down` — orchestrate the three scripts above; `down` runs `fly apps destroy`, `neonctl branches delete eph-<slug>`.
- Taskfile additions:
  ```yaml
  env:up:
    desc: Provision an ephemeral Fly.io test environment (backend only for now)
    cmd: ./scripts/ephemeral/up --name={{.NAME}}

  env:down:
    desc: Tear down an ephemeral Fly.io test environment
    cmd: ./scripts/ephemeral/down --name={{.NAME}}
  ```

**Depends on**: the one-time Neon `staging` branch (Third-party configuration).

**Verify**:
- `task env:up --name=<slug>` → a new Fly app and Neon branch are created with matching names; `smoke-test` passes.
- `task env:down --name=<slug>` → both are gone (`fly apps list`, `neonctl branches list`).
- `task test` unaffected — zero application code changed in this phase.

---

### Phase 3 — Code change: static-content host aliasing

**Goal**: make an arbitrary `*.fly.dev` hostname resolve to a static-content adapter instead of 404ing. Independently verifiable without Fly, Neon, or any of Phase 2's scripts.

**Builds**: `kaleidoscope-static-content-adapter-boot-instructions` in `env.clj` is currently an exact-hostname-keyed map (`andrewslai.com`, `kaleidoscope.pub`, …) — an unrecognized `*.fly.dev` host resolves to `nil` and 404s on every static asset. API/CRUD routes are unaffected (routing is already a catch-all regex). Add optional env vars, `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS` and `KALEIDOSCOPE_EPHEMERAL_HOST_BUCKET`, merged into the `"s3"` adapter map when set — same pattern as the existing `"andrewslai.com.localhost"` alias already in that function. No changes needed to `virtual_hosting.clj` (catch-all routing) or `authorization.clj` (role strings are already derived dynamically from the Host header).

**Verify**:
- Unit test on the boot-instructions function: with the env vars set, the returned map contains the alias host key pointing at an `S3` adapter for the given bucket.
- Manual local check: run the app locally with the env vars pointing at any existing bucket containing one test file, `curl -H "Host: fake-alias.fly.dev" localhost:5001/` and confirm the file is served instead of a 404.

---

### Phase 4 — Code change: S3 key-prefix support

**Goal**: let many ephemeral environments share one persistent bucket via key prefixes, instead of a bucket per environment. Independently verifiable without Fly or Phase 2/3's scripts.

**Builds**: `make-s3` (`persistence/filesystem/s3_impl.clj`) currently only accepts `{:bucket, :region}` — no way to scope one adapter instance to a subpath. Add an optional `:prefix` key: `get-file`/`put-file` prepend it to the S3 `Key` before every `GetObject`/`PutObject` call, no-op when unset (prod's bucket-per-host config, which never sets `:prefix`, is unaffected). Thread a new `KALEIDOSCOPE_EPHEMERAL_HOST_PREFIX` env var through the boot instructions alongside Phase 3's alias/bucket vars.

**New third-party setup**: create the persistent `kal-ephemeral` bucket once (see Third-party configuration below).

**Verify**:
- Unit test on `s3_impl.clj`: `:prefix` set → the `Key` sent to `GetObject`/`PutObject` includes the prefix; unset → behavior is unchanged from today.
- Manual check: upload a file to `kal-ephemeral/manual-test/index.html`, point a local run's alias/bucket/prefix env vars at it, confirm it's served, and confirm a request for a path outside the prefix isn't reachable.

---

### Phase 5 — Scripted frontend build + full integration

**Goal**: wire the real `kaleidoscope-ui` build and Phases 3–4's code changes into `env:up`/`env:down`. **This is the milestone that reaches full scope** (decision #3: backend + frontend).

**Builds**:
- `scripts/ephemeral/lib.sh` — extend the slug-derived resource names:
  - S3 key prefix: `eph-<slug>/` inside `kal-ephemeral`
  - `OTEL_SERVICE_NAME`: `kal-eph-<slug>` (gives full trace/log separation from prod in Tempo/Grafana with zero collector changes)
- `scripts/ephemeral/build-frontend` — `cd kaleidoscope-ui && npm run build-release` (→ `resources/kaleidoscope.pub/static/*`, same output `kaleidoscope-ui/scripts/deployment/deploy-kaleidoscope-pub` already syncs from), then `aws s3 sync ./build s3://kal-ephemeral/eph-<slug>/`.
- `scripts/ephemeral/deploy-app` — additionally sets `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS`/`_BUCKET`/`_PREFIX` as Fly secrets/env, derived from the slug.
- `scripts/ephemeral/smoke-test` — extended with `curl /` to confirm the static-content alias resolved and serves the built SPA, not a 404.
- `scripts/ephemeral/up` / `scripts/ephemeral/down` — `up` additionally calls `build-frontend`; `down` additionally runs `aws s3 rm s3://kal-ephemeral/eph-<slug>/ --recursive`.

**Depends on**: Phases 2, 3, and 4 all complete; the persistent `kal-ephemeral` bucket (Phase 4) and `staging` Neon branch (Phase 2) already exist.

**Verify**:
- `task env:up --name=<slug>` → Fly app, Neon branch, and S3 key prefix (`eph-<slug>/` in `kal-ephemeral`) all created; `smoke-test` passes end-to-end (`/ping` 200, `/` serves the built frontend, one authenticated write succeeds via Auth0 M2M).
- Manually browse to the printed `https://kal-eph-<slug>.fly.dev` URL, confirm the frontend loads and an interactive Auth0 login works — this is the point where the Auth0 wildcard callback-URL configuration (Third-party configuration below) actually matters; automated smoke tests never needed it.
- Confirm traces for the ephemeral run are visible in Grafana Tempo tagged with the per-environment `OTEL_SERVICE_NAME`, separate from `kaleidoscope-publishing`.
- `task env:down` → Fly app and Neon branch gone (`fly apps list`, `neonctl branches list`); S3 prefix empty (`aws s3 ls s3://kal-ephemeral/eph-<slug>/` returns nothing) while the shared bucket itself still exists.
- `task test` unaffected by any phase — no DB-related code changed, and the new `make-s3` `:prefix` option and host-alias env vars are all no-ops when unset, so existing embedded-H2/embedded-Postgres test suites, local dev, and prod's bucket-per-host config are all untouched by construction.

---

### Phase 6 (future, not built now) — GitHub Actions automation

Extend `.github/workflows/clojure.yml` with a PR-triggered job that calls the *same* `scripts/ephemeral/up --name=pr-<N>` / `down` scripts built in Phases 2–5 (the point of building CLI-first), with secrets mirrored into GitHub Actions, a teardown trigger on PR close, and a PR comment posting the ephemeral URL. Real-LLM steps should be gated behind a label or `workflow_dispatch` rather than running on every push, given the Anthropic cost note below.

---

## Third-party configuration needed

| System | What to configure | Needed by | Notes |
|---|---|---|---|
| **Neon** | Create one persistent `staging` branch, forked once from `main`, in the same Neon project as prod (Neon console or `neonctl branches create --name staging --parent main`) | Phase 1 | One-time, out-of-band prerequisite — not scripted. Every ephemeral branch forks from `staging`, never from `main`, so ephemeral test data is structurally isolated from live production data and no ephemeral run can write back to prod. |
| **Auth0** | Reuse the existing dev tenant/app (`dev-722l4eivlaenj2h1.us.auth0.com`, audience `https://api.andrewslai.com`) — no new tenant. One-time: create an `ephemeral:writer`/`:admin` role and assign it to the M2M test client (decision #6) — never repeated per slug. Verify whether **Allowed Callback URLs / Web Origins / Logout URLs** accept a wildcard (`https://*.fly.dev/*`) | Phase 1 (role setup), Phase 5 (interactive login) | Automated/CLI smoke testing (Phases 1–4) needs the one-time `ephemeral:writer` role, but no per-environment Auth0 change — it reuses the M2M client-credentials flow already proven in production (Checkly's canary uses it today), since `require-*-writer` admits `:service-account` identities. Only a human clicking through the actual frontend UI needs the callback URL registered. |
| **AWS** | Create one persistent `kal-ephemeral` S3 bucket, once, in the same AWS account as prod (console or `aws s3 mb s3://kal-ephemeral`); scope credentials to `s3:PutObject`/`GetObject`/`DeleteObject` on that bucket only — no `CreateBucket`/`DeleteBucket` needed since environments isolate via key prefix, not bucket lifecycle | Phase 4 | One-time, out-of-band prerequisite — not scripted, same shape as the Neon `staging` branch setup. Credentials stored in a new untracked `.env.fly.staging` file (same shape as existing `.env.fly.prod`, already covered by the `*.env*` gitignore rule). |
| **Anthropic** | Not needed — no `ANTHROPIC_API_KEY` exists on prod's Fly app either, since `KALEIDOSCOPE_SCORER_TYPE`/`KALEIDOSCOPE_WORKFLOW_EXECUTOR_TYPE` default to `"mock"` and prod never overrides them | N/A (opt-in, not phased) | If someone specifically wants to smoke-test the real AI features later, set both env vars to `"llm"` and add `ANTHROPIC_API_KEY` as a Fly secret on that one ephemeral app. See cost note below. |
| **Bugsnag** | Reuse the existing API key | Phase 1 | Pre-existing gap, not part of this plan: `BugsnagClient` never sets `releaseStage`, so ephemeral-env errors will show up tagged as `production` in the Bugsnag dashboard, indistinguishable from real prod errors. Worth a follow-up ticket. |
| **OTEL / Grafana Tempo / Sumo Logic** | No configuration needed | Phase 1 | `kaleidoscope-otel-collector` is reached via Fly's `.internal` 6PN network automatically for any app in the same org — zero extra config. Traces are separated from prod purely by the per-environment `OTEL_SERVICE_NAME`. **Sumo log shipping is explicitly out of scope** (the log shipper filters by `APP_NAME=kaleidoscope-publishing`) — use `fly logs -a <ephemeral-app>` directly instead. |
| **`.env.fly.staging`** (new, local, untracked) | `NEON_API_KEY` + `NEON_PROJECT_ID` (used by `neonctl` to create/delete branches and fetch each branch's connection string at `up`/`down` time), plus AWS credentials | Phase 2 (Neon), Phase 4 (AWS) | One-time manual setup by the developer. No static `KALEIDOSCOPE_DB_HOST/_NAME/_USER/_PASSWORD` lives here — each `env:up` fetches a fresh connection string for that run's branch via `neonctl connection-string eph-<slug>`. |

---

## Cost estimates (rough — verify current published pricing before relying on these)

Assuming **intermittent developer usage** — a handful of hours of actual running time per month, plus occasional smoke-test workflow runs:

- **Fly.io compute**: shared-cpu-1x + 1GB, scale-to-zero. Fly bills per-second only while a machine is running. Full-time (730 hrs/mo) this VM class costs roughly **$3–4/mo**; with scale-to-zero and only a few hours of actual test runtime per month, expect well under **$1/mo**.
- **Neon**: branches are copy-on-write — a branch only pays for pages that diverge from `staging`, so storage cost for a short-lived, lightly-mutated branch is negligible. Each branch gets its own compute endpoint, but on autosuspend it costs ~$0 while idle. Expect **$0–low single digits/mo** in marginal cost.
- **Auth0**: **$0** — reuses the existing dev tenant; M2M client-credentials calls don't consume the Auth0 free tier's MAU allotment the way interactive logins do.
- **AWS S3**: a few MB of JS bundles per environment, stored as a key prefix in the one shared `kal-ephemeral` bucket and deleted on teardown — **effectively $0** (well under a cent per environment-month). Sharing one bucket also avoids per-env `CreateBucket`/`DeleteBucket` calls entirely, which were the slowest and most rate-limit-prone part of a bucket-per-environment design.
- **Anthropic API**: the only cost that scales with *how much you test*, not with how long the environment exists. This app uses `claude-opus-4-6` ($5/$25 per million input/output tokens, no prompt caching). A single full `autonomous-team-review` workflow run (PM review + Eng review + Judge + Task generation, ~4-6 LLM calls) is roughly 15–30K input + 5–10K output tokens, i.e. **~$0.15–$0.35 per full workflow smoke test**. A lighter single-agent scoring call is a few cents. Running this in a tight loop (e.g. an automated per-commit CI job in Phase 6) is the one place cost could add up — worth gating behind a manual trigger or a `--mock-ai` flag on `env:up` for pure infra/plumbing test runs.

**Bottom line**: for on-demand, manually-triggered testing, total infra cost should be well under **$1/mo** by default (Anthropic is opt-in per decision #2, not part of the standard flow) — it only rises if and when someone deliberately opts an environment into real Anthropic-backed workflow runs.

---

## Explicitly out of scope (Phases 1–5)

- GitHub Actions / per-PR automation (Phase 6 — sketched above, not built).
- A separate Auth0 tenant.
- Sumo Logic log shipping for ephemeral apps (`fly logs -a <app>` instead).
- Automatic `kaleidoscope-ui` ref pinning — `env:up` builds whatever is currently checked out locally.
- Bugsnag `releaseStage` tagging fix (pre-existing gap, flagged for a follow-up ticket).
