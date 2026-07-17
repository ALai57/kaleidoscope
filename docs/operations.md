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
  the pinned tenant instead of inspecting the `Host` header.
- **Isolated assets.** `/static/*` and `/media/*` are served from
  `s3://kal-ephemeral/tenant-assets/<slug>/` — an S3 prefix scoped to this one
  ephemeral env (`KALEIDOSCOPE_TENANT_ASSET_BUCKET`/`_PREFIX`), never the real
  per-tenant bucket from `resources/tenants.json`.
  `task ephemeral:seed-tenant-assets NAME=<slug> TENANT=<hostname>` (run by
  `up`, before `deploy-app`) syncs
  `test-resources/ephemeral-sample-assets/<hostname>/` into that prefix, so
  ephemeral reads and writes never touch prod media.
- **Notifier disabled.** `deploy-app` sets `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none`
  so any image upload against an ephemeral env can't trigger the production
  resize/notification topic.
- **`KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS`/`_BUCKET`/`_PREFIX` are removed** —
  superseded by the fixed-resolver + tenant-asset vars above.
- **DB-seeding prerequisite.** The Neon branch (`provision-db`) must already
  contain the pinned tenant's rows — especially `themes` — or the env boots
  fine but renders empty content for that tenant. Ephemeral envs branch from
  `staging`, so keep `staging` seeded with every onboarded tenant's data.

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
