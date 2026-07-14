# Design: Playwright-first Checkly monitoring suite

- **Date:** 2026-07-14
- **Status:** Proposed (awaiting review)
- **Author:** Andrew Lai (with Claude)

---

## 1. Goal

Give Kaleidoscope a small set of synthetic checks that a human can run — or glance
at on a dashboard — and immediately read as a **feature status report**:

```
✓ The server is running and reports its version.
✓ The homepage loads and renders without client-side errors.
✓ Protected endpoints reject unauthenticated requests with 401.
✓ The synthetic user can obtain an Auth0 access token.
✓ Published articles can be listed and opened, and missing articles return 404.
✓ Recipes can be created, labeled, read, and deleted, and their invariants hold.
✗ Authenticated users can create, read, update, and delete a project.
– Scoring a project returns a score from Claude.   (skipped — paid, on-demand)
```

The value is **diagnostic, not exhaustive**. Reading that board should tell you
*"articles work, but projects are broken"* without opening a single log. Coverage
is deliberately shallow and broad: touch each feature and each third-party
dependency once, along its real path, with the smallest check that still proves
the path works end-to-end.

This is explicitly **not** a full integration test suite. Depth of behavioral
testing lives in the Clojure test suite (`task test`). These checks answer one
question per feature: *does this capability work right now, in this deployed
environment, against the real services it depends on?*

---

## 2. Design principles

These are the rules that shape every decision below. When in doubt, they win.

### 2.1 One suite = one answerable question

Each suite maps to a single user-visible capability (*articles*, *recipes*,
*project scoring*) or a single shared dependency (*Auth0 login*). It is not
organized by endpoint or by HTTP verb. A suite passes or fails as a unit, and
that pass/fail **is** the answer to one question about one feature.

The corollary — and the reason this document spends so much effort on wording —
is that **the suite's name is the diagnosis**. Names are precise declarative
sentences stating the guarantee ("Recipes can be created, labeled, read, and
deleted, and their invariants hold"), never vague labels ("recipes test"). When
a suite is red, its name should already tell you what stopped working.

### 2.2 Isolate shared dependencies so failures attribute correctly

Auth0 login is a precondition of the recipes, projects, and scoring suites. If
Auth0 has an outage, all three would go red at once and look like three broken
features. To prevent that misdiagnosis, **Auth0 login gets its own suite** — the
cheapest possible check (one token request, no app involvement). When it is red
next to the CRUD suites, you know the root cause is login, not the features.

The same logic separates *liveness* (`/ping`, is the process up?) from *homepage*
(does the UI actually render?). They fail for different reasons and point at
different fixes, so they are different questions and different suites.

### 2.3 Self-contained and idempotent

Every write-path suite creates the data it needs and deletes it in an `afterAll`
hook that runs even when assertions fail. No suite depends on data another suite
left behind, and running the whole thing twice in a row leaves no residue. The
only exception is read-only public content (articles, recipe lists), which exists
in production and — because ephemeral databases are Neon branches cloned from
prod — also exists in ephemeral environments.

### 2.4 Minimal surface, maximal signal, real paths

Prefer the cheapest check that still exercises the **real** dependency: the real
Neon database, the real Auth0 tenant, and — for the scoring suite — a real Claude
call. A check that only asserts a mock responded proves nothing about production.
Where a real call is expensive (Claude), bound its cost to the smallest possible
unit rather than skip it.

### 2.5 Fast and safe by default; expensive checks are opt-in

The default run — and every ephemeral branch deploy — must be fast and free. The
one suite that spends money (project scoring, a paid Claude call) is tagged
`spends`; the other seven are tagged `no-spend`. The default per-branch run
selects `no-spend`, so the paid suite runs only as a low-frequency production
monitor and never fires on a branch deploy. You never pay Anthropic to deploy a
branch. The tags name what is *true* of each suite — whether it makes an external
purchase — rather than the reason we route them differently, so the ephemeral
command `checkly test --tags no-spend` explains itself to a first-time reader.

### 2.6 Playwright-first, Checkly-thin

The tests are a standard Playwright project. They run with plain
`npx playwright test` against any URL, with no Checkly account and no Checkly
imports. Checkly is a thin scheduling layer on top: a single file of
`PlaywrightCheck` wrappers points at the Playwright project and runs it on
Checkly's cloud on a schedule. If we ever leave Checkly, the tests come with us
unchanged. See §5 for why this matters and what it costs.

---

## 3. The suites

Eight suites. The table is the contract; the sections after it add the detail
that does not fit in a row.

| # | Suite (declarative name) | Proves | A failure means | Real dependencies | Cost |
|---|---|---|---|---|---|
| 1 | **The server is running and reports its version.** | `/ping` returns 200 with a non-null version. | The app is down, crash-looping, or the deploy never came up. Treat every other result as unreliable. | Fly host + Ring server | Trivial |
| 2 | **The homepage loads and renders without client-side errors.** | The homepage returns 200, has a title, settles its network, and throws no uncaught JS errors. | Static content isn't being served (S3 / frontend build / deploy) or the app shell crashes in the browser. The site is down for humans even if #1 is green. | Fly host + static-content store (S3 in prod) + browser runtime | Heaviest (Chromium) |
| 3 | **Protected endpoints reject unauthenticated requests with 401.** | Unauthenticated calls to `/articles`, `/projects`, `/workflows`, `/agents` return 401 — not 200, not 500. | Either the auth wall is open (a 200 is a security hole) or the middleware is crashing (a 500). Distinguishes *insecure* from *broken*. | Auth middleware | Trivial |
| 4 | **The synthetic user can obtain an Auth0 access token.** | The client-credentials exchange with Auth0 returns a token for the API audience. | Auth0 is down, or the M2M client/secret/audience config drifted. Isolates the login dependency the write-path suites all share. | Auth0 (does not touch the app) | Trivial |
| 5 | **Published articles can be listed and opened, and missing articles return 404.** | `/compositions` lists articles with expected fields; a specific article opens with non-empty content; an unknown slug 404s. | The public CMS read path or its query is broken — or this environment's database has no article content. | App + Neon (read) | Low |
| 6 | **Recipes can be created, labeled, read, and deleted, and their invariants hold.** | Full authenticated recipe lifecycle, plus two invariants: at most one label per label-group (400 on violation) and the SSRF guard blocks internal URLs on scrape (422). Public read, authenticated write. | The recipe domain is broken — CRUD, the one-label-per-group rule, or the SSRF protection. A red SSRF step is a security regression. | App + Neon (read/write) + Auth0 | Low |
| 7 | **Authenticated users can create, read, update, and delete a project.** | Full project CRUD; a deleted project then 404s. | The projects persistence/API layer is broken — which also undermines scoring and workflows built on top of it. | App + Neon (read/write) + Auth0 | Low |
| 8 | **Scoring a project returns a score from Claude.** | Create a project and a one-dimension score definition, trigger scoring, and receive a real score back from a live Claude call. | The AI scoring path is broken: bad/expired Anthropic key, model unavailable, scorer misconfigured (running `mock` when it should be `llm`), or the response contract changed. | App + Neon + Auth0 + **Anthropic (real, paid)** | One bounded Claude call |

### 3.1 Why these eight, and why not more

The set covers, exactly once each: liveness, the rendered site, the security
boundary, the shared login dependency, the two public/CMS read domains
(articles), the two authenticated write domains (recipes, projects), and the
headline AI feature (scoring). Every third-party dependency Kaleidoscope relies
on in production — Fly, the static-content store, Auth0, Neon, Anthropic — is
exercised by at least one suite along its real path.

Deliberately **excluded** (see §8): workflow execution (SSE-streamed, expensive,
and largely a superset of scoring for monitoring purposes), multi-tenant host
routing, photo/album upload, and anything requiring per-run fixtures we would
have to seed and tear down beyond a single entity.

### 3.2 The scoring suite in detail (suite 8)

This is the only suite that spends money, so its cost is bounded by construction:

1. Obtain an Auth0 token (same exchange as suite 4).
2. Create a synthetic project.
3. Create a **single-dimension** score definition. One dimension means the
   scoring endpoint makes exactly one Claude call with the smallest useful
   prompt — this is the cost cap.
4. `POST /projects/:id/scores` with that one `definition-id`. The endpoint runs
   the Claude call **synchronously** and returns JSON (no SSE to parse), and is
   rate-limited server-side (5/min) as a second guardrail.
5. Assert 200 and that a score comes back.
6. `afterAll`: delete the project and the score definition.

It proves the entire chain in one shot — key valid, model reachable, scorer wired
as `llm`, prompt built, response parsed. Because production sets
`KALEIDOSCOPE_SCORER_TYPE=llm` (in `fly.toml`), this hits real Claude in prod;
because ephemeral environments inherit that same setting, it would *also* hit real
Claude there — which is exactly why it is fenced off from the per-branch run (§6).

---

## 4. Architecture and file layout

The tests are a standard Playwright project. Checkly is one thin file on top.

```
checkly/
  playwright.config.ts        # NEW — standard Playwright config
  package.json                # + @playwright/test devDependency
  checkly.config.ts           # unchanged (project/account settings)
  __checks__/
    suites.check.ts           # NEW — all PlaywrightCheck wrappers, declared as one table
    tests/                    # pure Playwright specs — zero Checkly imports
      liveness.spec.ts        # suite 1  (replaces the native ApiCheck on /ping)
      homepage.spec.ts        # suite 2  (moved; still a real browser render)
      auth-boundary.spec.ts   # suite 3  (moved as-is)
      auth0-login.spec.ts     # suite 4  (NEW — isolates the login dependency)
      articles.spec.ts        # suite 5  (was compositions.spec.ts)
      recipes.spec.ts         # suite 6  (moved as-is)
      projects.spec.ts        # suite 7  (was project-lifecycle.spec.ts)
      scoring.spec.ts         # suite 8  (NEW — bounded real Claude call)
    lib/
      auth.ts                 # shared Auth0 client-credentials helper
```

- **`playwright.config.ts`** sets `use.baseURL` from `process.env.ENVIRONMENT_URL`
  (defaulting to production, matching today's `config.ts`), so specs use relative
  paths (`page.goto('/')`, `request.get('/recipes')`). It defines **one Playwright
  project per suite** via `testMatch`, so each suite is selectable by name with
  plain `npx playwright test --project=<name>`.
- **`suites.check.ts`** maps over a single array of
  `{ name, project, frequency, locations, tags }` and emits one `PlaywrightCheck`
  per row. The entire monitor topology — what runs, how often, where, and under
  which tag — is visible in one place. Each wrapper selects its tests with
  `pwProjects: ['<name>']`; the tests themselves never mention Checkly.
- **`lib/auth.ts`** holds the one piece of shared logic (the Auth0 token
  exchange) so suites 4, 6, 7, and 8 don't each re-implement it.

### 4.1 Naming discipline (this is load-bearing)

Every suite name, and every `test()` / `test.step()` description inside it, is a
precise declarative sentence describing the guarantee — following the convention
already established in the existing checks ("Unauthenticated users cannot access
protected resources"). The rule: **someone reading only the green/red output,
with no access to the code, should be able to state exactly what does and does not
work.** Casual paraphrases ("check recipes", "auth test") are not acceptable
because they force the reader back into the code to learn what broke.

---

## 5. Why Playwright-first, and what it costs

**Why.** Today the specs import `@playwright/test` but nothing installs it — they
only run inside Checkly's bundled runtime. They cannot run without Checkly. The
`PlaywrightCheck` model inverts that: the specs become a real, standalone
Playwright project (`playwright.config.ts` + `@playwright/test` dependency) that
runs anywhere, and Checkly becomes a scheduler pointed at it. Selection is
Playwright-native (`--project` / tags), so even the filtering logic is portable.
If we leave Checkly, the suite leaves with us, unchanged.

**What it costs.**

- **New surface to maintain:** a `playwright.config.ts` and a `@playwright/test`
  devDependency. Offset by the ability to run the exact suite locally against any
  environment with no Checkly account.
- **Monitoring granularity:** a `PlaywrightCheck` reports one pass/fail for
  whatever it runs. We preserve per-feature granularity by giving **each suite its
  own wrapper** (one `PlaywrightCheck` per Playwright project) rather than one big
  suite — this is the whole point of §2.1 and is why there are eight wrappers, not
  one. The trade-off we accept: eight thin wrapper rows instead of eight native
  check constructs. The tests are identical either way.

The native `ApiCheck` on `/ping` (suite 1) becomes a one-line Playwright test
using the `request` fixture — a negligible change, and worth it to keep the whole
suite under one runner and one portability story.

---

## 6. Environments and selection

| Environment | Target URL | Scorer | What runs |
|---|---|---|---|
| **Production** | `sahiltalkingcents.com` | `llm` (real) | All eight suites as scheduled monitors, via `checkly deploy`. Suites 1–7 at 6h; suite 8 daily. |
| **Ephemeral** (per branch) | `kal-eph-<slug>.fly.dev` | `llm` (real) | Suites 1–7 only, via `checkly test --tags no-spend` in `scripts/ephemeral/checkly-test`. Suite 8 excluded — no paid call on branch deploys. |
| **On-demand** | any (`ENVIRONMENT_URL`) | depends on target | Anything, manually: `checkly test --tags spends`, or Checkly-free `ENVIRONMENT_URL=<url> npx playwright test --project=scoring`. |

**Tagging.** Suites 1–7 carry the Checkly tag `no-spend`; suite 8 carries `spends`
(and deliberately not `no-spend`). The tags describe whether the suite makes a
paid external call, not why we schedule it a certain way. Because
`checkly test --tags` is include-only, exclusion is achieved by *not* tagging the
paid suite `no-spend`, and pointing the ephemeral runner at `--tags no-spend`.

**The one script change.** `scripts/ephemeral/checkly-test` changes its invocation
from `npx checkly test …` to `npx checkly test --tags no-spend …`. Everything else
in that script (the Auth0/Checkly credential gating, the `ENVIRONMENT_URL`
plumbing) stays as-is.

**Runtime env vars** (unchanged from today): `ENVIRONMENT_URL`, `AUTH0_CLIENT_ID`,
`AUTH0_CLIENT_SECRET`, supplied via `--env` for `checkly test` and via Checkly's
stored environment variables for the deployed monitors. All secrets remain
gitignored (`*.env*`) and supplied at runtime — never committed.

---

## 7. Operational and documentation changes

Per CLAUDE.md Sharp edge #6, deployment/ops changes are reflected in
`docs/operations.md` in the same change. This work touches:

- The ephemeral regression command (`checkly test` → `checkly test --tags
  no-spend`) and what it now does / does not run.
- The new daily production monitor (suite 8) and the fact that it makes a real,
  paid Claude call.
- The on-demand commands for validating the AI path against a non-prod
  environment.

---

## 8. Out of scope (deferred, with reasons)

- **Multi-tenant host routing.** High value and currently untested, but ephemeral
  environments serve a single `kal-eph-<slug>` host, so a meaningful host-header
  test behaves differently there than in prod. Worth its own design.
- **Workflow execution (SSE).** Streaming responses are awkward to assert and, for
  monitoring purposes, largely a superset of the scoring path already covered by
  suite 8. Revisit if workflows break in ways scoring does not catch.
- **Photo / album upload, portfolio, admin.** Legacy CMS surface; low change-rate;
  not worth the fixture and cleanup cost for a smoke check.

---

## 9. Risks and open questions

- **Ephemeral database contents.** Suites 5–7 assume the target DB has (or accepts
  writes for) the entities they touch. Ephemeral clones prod, so read-only suite 5
  holds; the write-path suites create their own data, so they hold regardless.
  Watch for environments provisioned from an *empty* DB — suite 5 would then
  correctly report "no article content," which is a true negative, not a false
  one.
- **Scoring cost drift.** Suite 8 is bounded to one Claude call by the
  single-dimension definition. If the scoring endpoint's per-call behavior changes
  (e.g. multiple calls per dimension), revisit the bound.
- **`checkly deploy` cadence.** The scheduled monitors only exist once
  `checkly deploy` runs. If that is manual today, adding suite 8 requires a deploy
  to take effect. (Confirm whether deploy is wired into CI or run by hand.)

---

## 10. Post-implementation corrections

Two premises in this design proved wrong against the live backend during the
final review and were corrected in the implementation:

1. **Recipe content shape.** §3 suite 6 assumed the old flat `content`
   (`{ ingredients, instructions-html }`). The current `RecipeContent` model
   (`models/recipes.cljc`) requires `sections: [{ ingredients, steps }]` as the
   sole representation; the flat shape returns 400. The recipes suite now sends
   and asserts the sections shape.

2. **Project-creation is not free.** §2.5/§6 assumed only the `scoring` suite
   spends. In fact `POST /projects` (`api/projects.clj` `create-project!`) fans
   out to default-definition scoring **and** the default workflow, so in an `llm`
   environment the `projects` suite also makes paid Claude calls. The `projects`
   suite is therefore tagged **`spends`**, not `no-spend`. Net tags: six
   `no-spend` (liveness, homepage, auth-boundary, auth0-login, articles, recipes)
   and two `spends` (projects, scoring). The ephemeral `--tags no-spend` run
   covers six suites; project CRUD is exercised by the production monitors and
   on-demand `--tags spends` runs. `docs/operations.md` reflects this.
