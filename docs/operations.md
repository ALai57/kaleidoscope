# Operations

Operational concerns for running Kaleidoscope in production. This document
covers how the app is set up to operate, the guardrails around that setup, and
the decisions behind them.

> **Maintenance:** Keep this document current. Any change to deployment
> (`fly.toml`, `bin/` deploy scripts, Docker/build, secrets/env) or to the
> Taskfile / `bin/` interface must be reflected here in the same change.

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

| Context | Command | Runs |
|---|---|---|
| Ephemeral `up` | `checkly test --tags no-spend` (in `scripts/ephemeral/checkly-test`) | 6 free suites |
| On-demand LLM check | `checkly test --tags spends --env ENVIRONMENT_URL=<url>` | projects + scoring (paid) |
| Production monitors | `checkly deploy` | all 8; scoring daily, rest every 6h |

**Deployed monitors need these Checkly environment variables** (set once in the
Checkly account, not committed): `AUTH0_CLIENT_ID`, `AUTH0_CLIENT_SECRET`.
`ENVIRONMENT_URL` is omitted in production so checks default to the live site.

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
