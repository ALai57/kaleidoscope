# Operations

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
