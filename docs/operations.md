# Operations

Operational concerns for running Kaleidoscope in production. This document
covers how the app is set up to operate, the guardrails around that setup, and
the decisions behind them.

> **Maintenance:** Keep this document current. Any change to deployment
> (`fly.toml`, `bin/` deploy scripts, Docker/build, secrets/env) or to the
> Taskfile / `bin/` interface must be reflected here in the same change.

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
