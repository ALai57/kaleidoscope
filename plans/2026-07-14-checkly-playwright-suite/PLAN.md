# Playwright-first Checkly Suite Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restructure the Checkly checks into a standalone, portable Playwright project of eight feature-diagnostic suites, with the paid Claude scoring suite fenced out of the per-branch run.

**Architecture:** The tests become a standard Playwright project (`checkly/playwright.config.ts` + `@playwright/test`) that runs anywhere with `npx playwright test` and never imports Checkly. One thin `suites.check.ts` declares eight `PlaywrightCheck` wrappers — one per Playwright project — so Checkly is only a scheduler. Suites are tagged `no-spend` (seven free suites) or `spends` (the one paid Claude suite); the ephemeral runner selects `--tags no-spend`.

**Tech Stack:** TypeScript, Playwright Test (`@playwright/test`), Checkly CLI v8.10 (`PlaywrightCheck` construct), Node 22.

## Global Constraints

- **Design reference:** `plans/2026-07-14-checkly-playwright-suite/DESIGN.md`. The §3 suite names are the exact, load-bearing `test(...)` titles — copy them verbatim.
- **Base URL:** every spec targets `process.env.ENVIRONMENT_URL`, defaulting to `https://sahiltalkingcents.com`. Specs use **relative** paths (`request.get('/recipes')`, `page.goto('/')`); the default comes from Playwright's `use.baseURL`.
- **Wire format is kebab-case.** Request bodies use hyphenated keys (`'definition-ids'`, `'recipe-url'`, `'label-ids'`); response bodies are kebab-case (`score-definition-id`, `article-url`).
- **Specs are pure Playwright** — no `checkly/constructs` imports in anything under `__checks__/tests/`.
- **Tags:** suites 1–7 → `['no-spend']`; suite 8 → `['spends']`. `checkly test --tags` is include-only; exclusion of the paid suite is achieved by selecting `no-spend`.
- **Never add browser-install commands** (`playwright install`) to check code — Checkly's cloud runners have browsers pre-installed. Local dev installs them once, separately.
- **`playwrightConfigPath` on a `PlaywrightCheck` resolves relative to the `.check.ts` file that declares it** — from `__checks__/suites.check.ts` the config is `'../playwright.config.ts'`.
- **Auth0 (M2M):** domain `dev-722l4eivlaenj2h1.us.auth0.com`, audience `https://api.andrewslai.com`, credentials from `AUTH0_CLIENT_ID` / `AUTH0_CLIENT_SECRET` env vars.
- **Secrets come from the environment only** — never commit `.env*` (already gitignored via `*.env*`).
- **Node 22** (`checkly/.nvmrc`). Run all commands below from `checkly/` unless stated.

**Verification credentials:** the write-path suites (recipes, projects, scoring) and the auth0-login suite need real Auth0 creds. For local verification, load them from the staging env file:
```bash
set -a; source ../.env.fly.staging; set +a   # provides AUTH0_CLIENT_ID / AUTH0_CLIENT_SECRET
```
These suites create synthetic data on the **real** target (default: production) and delete it in `afterAll` — this is the intended monitoring design.

---

## File Structure

```
checkly/
  package.json                       # MODIFY — add @playwright/test devDependency
  playwright.config.ts               # CREATE — baseURL + one project per suite
  checkly.config.ts                  # unchanged
  __checks__/
    suites.check.ts                  # CREATE — the eight PlaywrightCheck wrappers
    tests/
      lib/auth.ts                    # CREATE — Auth0 client-credentials helper
      liveness.spec.ts               # CREATE (suite 1)
      homepage.spec.ts               # CREATE (suite 2; from old homepage.spec.ts)
      auth-boundary.spec.ts          # CREATE (suite 3; from old auth-boundary.spec.ts)
      auth0-login.spec.ts            # CREATE (suite 4)
      articles.spec.ts               # CREATE (suite 5; from old compositions.spec.ts)
      recipes.spec.ts                # CREATE (suite 6; from old recipes.spec.ts)
      projects.spec.ts               # CREATE (suite 7; from old project-lifecycle.spec.ts)
      scoring.spec.ts                # CREATE (suite 8)
  __checks__/{health,homepage,auth-boundary,compositions,project-lifecycle,recipes}.check.ts  # DELETE
  __checks__/{homepage,auth-boundary,compositions,project-lifecycle,recipes}.spec.ts          # DELETE
  __checks__/config.ts               # DELETE (replaced by playwright baseURL)
scripts/ephemeral/checkly-test       # MODIFY — add `--tags no-spend`
docs/operations.md                   # MODIFY — document the suite, tags, and commands
```

**Why `tests/*.spec.ts` won't double-register as Checkly checks:** Checkly only auto-discovers `.spec.ts` as browser checks when `browserChecks.testMatch` is set in `checkly.config.ts` (verified in `project-parser.js`: `loadAllBrowserChecks` returns early on a falsy pattern). It is not set, so the specs are consumed only by the `PlaywrightCheck` wrappers via `playwrightConfigPath`.

---

## Task 1: Standalone Playwright project + liveness suite

Establishes the portable foundation: a real Playwright project that runs with zero Checkly, proven by the simplest new suite.

**Files:**
- Modify: `checkly/package.json`
- Create: `checkly/playwright.config.ts`
- Create: `checkly/__checks__/tests/liveness.spec.ts`

**Interfaces:**
- Produces: Playwright projects named `liveness`, `homepage`, `auth-boundary`, `auth0-login`, `articles`, `recipes`, `projects`, `scoring` (later tasks add the specs each matches). `use.baseURL` from `ENVIRONMENT_URL`.

- [ ] **Step 1: Add the Playwright dependency**

Edit `checkly/package.json` `devDependencies` to add `@playwright/test`:

```json
{
  "name": "kaleidoscope-checkly",
  "version": "1.0.0",
  "scripts": {
    "test": "npx checkly test",
    "deploy": "npx checkly deploy"
  },
  "devDependencies": {
    "@playwright/test": "^1.49.0",
    "checkly": "^8.10.0",
    "typescript": "^5.0.0"
  }
}
```

- [ ] **Step 2: Install it (and the local browser, once)**

Run (from `checkly/`):
```bash
npm install
npx playwright install chromium
```
Expected: install completes; `node_modules/@playwright/test` exists.

- [ ] **Step 3: Create the Playwright config**

Create `checkly/playwright.config.ts`:

```ts
import { defineConfig, devices } from '@playwright/test'

// Every suite targets this base URL. Defaults to production so the scheduled
// monitors watch the live site; override with ENVIRONMENT_URL to point at an
// ephemeral or staging environment.
const BASE_URL = process.env.ENVIRONMENT_URL ?? 'https://sahiltalkingcents.com'

export default defineConfig({
  testDir: './__checks__/tests',
  timeout: 30_000,
  expect: { timeout: 10_000 },
  use: {
    baseURL: BASE_URL,
    ...devices['Desktop Chrome'],
  },
  // One project per feature suite. Each maps to a single spec so it can be run
  // and scheduled in isolation (npx playwright test --project=<name>).
  projects: [
    { name: 'liveness',      testMatch: /liveness\.spec\.ts/ },
    { name: 'homepage',      testMatch: /homepage\.spec\.ts/ },
    { name: 'auth-boundary', testMatch: /auth-boundary\.spec\.ts/ },
    { name: 'auth0-login',   testMatch: /auth0-login\.spec\.ts/ },
    { name: 'articles',      testMatch: /articles\.spec\.ts/ },
    { name: 'recipes',       testMatch: /recipes\.spec\.ts/ },
    { name: 'projects',      testMatch: /projects\.spec\.ts/ },
    { name: 'scoring',       testMatch: /scoring\.spec\.ts/ },
  ],
})
```

- [ ] **Step 4: Write the liveness spec**

Create `checkly/__checks__/tests/liveness.spec.ts`:

```ts
import { test, expect } from '@playwright/test'

// Suite 1 — is the deployed process up and serving HTTP?
test('The server is running and reports its version', async ({ request }) => {
  const res = await request.get('/ping')
  expect(res.status()).toBe(200)
  const body = await res.json()
  expect(body.version, 'The /ping response has no version').toBeTruthy()
})
```

- [ ] **Step 5: Run it against production — Checkly-free**

Run (from `checkly/`):
```bash
npx playwright test --project=liveness
```
Expected: `1 passed`. This proves the standalone Playwright project works with no Checkly involvement.

- [ ] **Step 6: Commit**

```bash
git add checkly/package.json checkly/package-lock.json checkly/playwright.config.ts checkly/__checks__/tests/liveness.spec.ts
git commit -m "feat(checkly): standalone Playwright project + liveness suite

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 2: Auth0 login helper + login suite

Adds the shared login helper (used by suites 6–8) and the standalone Auth0 suite that isolates the login dependency.

**Files:**
- Create: `checkly/__checks__/tests/lib/auth.ts`
- Create: `checkly/__checks__/tests/auth0-login.spec.ts`

**Interfaces:**
- Produces:
  - `getAccessToken(request: APIRequestContext): Promise<string>` — performs the client-credentials exchange, asserts 200, returns the raw `access_token`.
  - `authHeaders(request: APIRequestContext): Promise<Record<string, string>>` — returns `{ Authorization: 'Bearer <token>' }`.

- [ ] **Step 1: Write the auth helper**

Create `checkly/__checks__/tests/lib/auth.ts`:

```ts
import { APIRequestContext, expect } from '@playwright/test'

const AUTH0_DOMAIN = 'dev-722l4eivlaenj2h1.us.auth0.com'
const AUDIENCE     = 'https://api.andrewslai.com'

// Client-credentials (M2M) exchange for the synthetic monitoring user.
export async function getAccessToken(request: APIRequestContext): Promise<string> {
  const res = await request.post(`https://${AUTH0_DOMAIN}/oauth/token`, {
    data: {
      grant_type:    'client_credentials',
      client_id:     process.env.AUTH0_CLIENT_ID,
      client_secret: process.env.AUTH0_CLIENT_SECRET,
      audience:      AUDIENCE,
    },
  })
  expect(res.status(), 'The synthetic test user could not log in').toBe(200)
  const { access_token } = await res.json()
  return access_token
}

export async function authHeaders(request: APIRequestContext): Promise<Record<string, string>> {
  return { Authorization: `Bearer ${await getAccessToken(request)}` }
}
```

- [ ] **Step 2: Write the login suite**

Create `checkly/__checks__/tests/auth0-login.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { getAccessToken } from './lib/auth'

// Suite 4 — isolates the shared Auth0 dependency so a login outage shows up as
// one red line instead of reddening every write-path suite at once.
test('The synthetic user can obtain an Auth0 access token', async ({ request }) => {
  const token = await getAccessToken(request)
  expect(token, 'Auth0 returned no access token').toBeTruthy()
})
```

- [ ] **Step 3: Run it against production (needs creds)**

Run (from `checkly/`):
```bash
set -a; source ../.env.fly.staging; set +a
npx playwright test --project=auth0-login
```
Expected: `1 passed`. If it fails on status 401/403, the M2M client/secret is wrong or the grant is misconfigured — fix creds before proceeding.

- [ ] **Step 4: Commit**

```bash
git add checkly/__checks__/tests/lib/auth.ts checkly/__checks__/tests/auth0-login.spec.ts
git commit -m "feat(checkly): Auth0 login helper and login suite

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 3: Migrate the read-only public suites

Moves homepage, auth-boundary, and articles into the new project, converting them to relative URLs (baseURL). No credentials required.

**Files:**
- Create: `checkly/__checks__/tests/homepage.spec.ts`
- Create: `checkly/__checks__/tests/auth-boundary.spec.ts`
- Create: `checkly/__checks__/tests/articles.spec.ts`
- Delete: `checkly/__checks__/homepage.spec.ts`, `checkly/__checks__/auth-boundary.spec.ts`, `checkly/__checks__/compositions.spec.ts`

**Interfaces:**
- Consumes: Playwright projects `homepage`, `auth-boundary`, `articles` from Task 1.

- [ ] **Step 1: Write the homepage suite**

Create `checkly/__checks__/tests/homepage.spec.ts`:

```ts
import { test, expect } from '@playwright/test'

// Suite 2 — does the SPA actually render for a human (static content served,
// app shell boots, no uncaught JS)?
test('The homepage loads and renders without client-side errors', async ({ page }) => {
  const errors: string[] = []
  page.on('pageerror', (e) => errors.push(e.message))

  await test.step('The homepage loads successfully', async () => {
    const response = await page.goto('/')
    expect(response?.status()).toBe(200)
    await page.waitForLoadState('networkidle')
  })

  await test.step('The page has a title', async () => {
    await expect(page).toHaveTitle(/.+/)
  })

  await test.step('The page has no unhandled JavaScript errors', async () => {
    expect(errors, `JS errors on homepage: ${errors.join(', ')}`).toHaveLength(0)
  })
})
```

- [ ] **Step 2: Write the auth-boundary suite**

Create `checkly/__checks__/tests/auth-boundary.spec.ts`:

```ts
import { test, expect } from '@playwright/test'

// Suite 3 — is the auth wall running and returning 401, not 200 (open) or 500
// (crashing)?
test('Protected endpoints reject unauthenticated requests with 401', async ({ request }) => {
  const endpoints: Array<{ method: 'get' | 'post', path: string, description: string }> = [
    { method: 'get',  path: '/articles',  description: 'Listing articles requires authentication' },
    { method: 'get',  path: '/projects',  description: 'Listing projects requires authentication' },
    { method: 'post', path: '/workflows', description: 'Starting a workflow requires authentication' },
    { method: 'get',  path: '/agents',    description: 'Listing agents requires authentication' },
  ]

  for (const { method, path, description } of endpoints) {
    await test.step(description, async () => {
      const res = await request[method](path)
      expect(res.status(), `Expected 401 from ${method.toUpperCase()} ${path}`).toBe(401)
    })
  }
})
```

- [ ] **Step 3: Write the articles suite**

Create `checkly/__checks__/tests/articles.spec.ts`:

```ts
import { test, expect } from '@playwright/test'

// Suite 5 — does the public CMS read path work end-to-end?
test('Published articles can be listed and opened, and missing articles return 404', async ({ request }) => {
  let first: { 'article-url': string }

  await test.step('The list of articles loads and is not empty', async () => {
    const listRes = await request.get('/compositions')
    expect(listRes.status()).toBe(200)
    const articles = await listRes.json()
    expect(Array.isArray(articles)).toBe(true)
    expect(articles.length).toBeGreaterThan(0)
    first = articles[0]
    expect(first).toHaveProperty('article-url')
    expect(first).toHaveProperty('article-title')
    expect(first).toHaveProperty('hostname')
  })

  await test.step('The first article can be opened', async () => {
    const slug = first['article-url']
    const articleRes = await request.get(`/compositions/${encodeURIComponent(slug)}`)
    expect(articleRes.status()).toBe(200)
    const article = await articleRes.json()
    expect(article['article-url']).toBe(slug)
    expect(typeof article.content).toBe('string')
    expect(article.content.length).toBeGreaterThan(0)
  })

  await test.step('Opening an article that does not exist returns a 404', async () => {
    const missingRes = await request.get('/compositions/checkly-nonexistent-xyz')
    expect(missingRes.status()).toBe(404)
  })
})
```

- [ ] **Step 4: Delete the old copies**

```bash
git rm checkly/__checks__/homepage.spec.ts checkly/__checks__/auth-boundary.spec.ts checkly/__checks__/compositions.spec.ts
```

- [ ] **Step 5: Run all three against production**

Run (from `checkly/`):
```bash
npx playwright test --project=homepage --project=auth-boundary --project=articles
```
Expected: `3 passed`.

- [ ] **Step 6: Commit**

```bash
git add checkly/__checks__/tests/homepage.spec.ts checkly/__checks__/tests/auth-boundary.spec.ts checkly/__checks__/tests/articles.spec.ts
git commit -m "refactor(checkly): migrate read-only suites to Playwright project

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 4: Migrate the authenticated write suites

Moves recipes and projects, converting them to relative URLs and the shared `authHeaders` helper.

**Files:**
- Create: `checkly/__checks__/tests/recipes.spec.ts`
- Create: `checkly/__checks__/tests/projects.spec.ts`
- Delete: `checkly/__checks__/recipes.spec.ts`, `checkly/__checks__/project-lifecycle.spec.ts`

**Interfaces:**
- Consumes: `authHeaders` from Task 2; Playwright projects `recipes`, `projects` from Task 1.

- [ ] **Step 1: Write the recipes suite**

Create `checkly/__checks__/tests/recipes.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { authHeaders } from './lib/auth'

// Wire format is kebab-case (backend Malli models are kebab-keyed).
let headers: Record<string, string> = {}
let recipeUrl: string | null = null
let groupId: string | null = null
const labelIds: string[] = []

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  headers = await authHeaders(request)
})

// Always clean up, even if assertions fail mid-test.
test.afterAll('Remove any synthetic recipe, labels, and group that remain', async ({ request }) => {
  if (recipeUrl) await request.delete(`/recipes/${recipeUrl}`, { headers })
  for (const id of labelIds) await request.delete(`/recipe-labels/${id}`, { headers })
  if (groupId) await request.delete(`/recipe-label-groups/${groupId}`, { headers })
})

test('Recipes can be created, labeled, read, and deleted, and their invariants hold', async ({ request }) => {
  const slug = `checkly-synthetic-recipe-${Date.now()}`

  await test.step('Create a new recipe', async () => {
    const res = await request.post('/recipes', {
      headers,
      data: {
        'recipe-url': slug,
        content: {
          title: 'Checkly Synthetic Recipe',
          ingredients: ['1 cup flour', '2 eggs'],
          'instructions-html': '<ol><li>Mix</li><li>Bake</li></ol>',
        },
        'public-visibility': true,
      },
    })
    expect(res.status()).toBe(200)
    expect((await res.json())['recipe-url']).toBe(slug)
    recipeUrl = slug
  })

  await test.step('View the newly created recipe with its parsed content', async () => {
    const res = await request.get(`/recipes/${slug}`, { headers })
    expect(res.status()).toBe(200)
    const recipe = await res.json()
    expect(recipe.content.title).toBe('Checkly Synthetic Recipe')
    expect(recipe.content.ingredients).toContain('1 cup flour')
  })

  await test.step('At most one label from a group can apply to a recipe', async () => {
    const groupRes = await request.post('/recipe-label-groups', {
      headers, data: { name: `checkly-ethnicity-${Date.now()}` },
    })
    expect(groupRes.status()).toBe(200)
    groupId = (await groupRes.json()).id

    const indianRes  = await request.post('/recipe-labels', { headers, data: { name: `indian-${Date.now()}`,  'group-id': groupId } })
    const mexicanRes = await request.post('/recipe-labels', { headers, data: { name: `mexican-${Date.now()}`, 'group-id': groupId } })
    expect(indianRes.status()).toBe(200)
    expect(mexicanRes.status()).toBe(200)
    const indianId  = (await indianRes.json()).id
    const mexicanId = (await mexicanRes.json()).id
    labelIds.push(indianId, mexicanId)

    await test.step('Two labels from the same group are rejected with a 400', async () => {
      const res = await request.put(`/recipes/${slug}`, { headers, data: { 'label-ids': [indianId, mexicanId] } })
      expect(res.status()).toBe(400)
    })

    await test.step('A single label from the group is accepted', async () => {
      const res = await request.put(`/recipes/${slug}`, { headers, data: { 'label-ids': [indianId] } })
      expect(res.status()).toBe(200)
    })
  })

  await test.step('Scraping a disallowed URL is rejected without fetching it', async () => {
    // 169.254.169.254 is the cloud metadata endpoint; the SSRF guard must block
    // it and return 422 rather than fetching.
    const res = await request.post('/recipes/scrape', {
      headers, data: { url: 'http://169.254.169.254/latest/meta-data/' },
    })
    expect(res.status()).toBe(422)
  })

  await test.step('Delete the recipe', async () => {
    const res = await request.delete(`/recipes/${slug}`, { headers })
    expect(res.status()).toBe(200)
    recipeUrl = null
  })

  await test.step('Confirm the recipe no longer exists', async () => {
    const res = await request.get(`/recipes/${slug}`, { headers })
    expect(res.status()).toBe(404)
  })
})

test('Recipes are readable publicly but only writable by authenticated users', async ({ request }) => {
  await test.step('Anyone can list recipes', async () => {
    const res = await request.get('/recipes')
    expect(res.status()).toBe(200)
    expect(Array.isArray(await res.json())).toBe(true)
  })

  await test.step('Anyone can list recipe labels', async () => {
    const res = await request.get('/recipe-labels')
    expect(res.status()).toBe(200)
  })

  await test.step('Creating a recipe without a token is rejected with a 401', async () => {
    const res = await request.post('/recipes', { data: { content: { title: 'unauthorized', ingredients: [] } } })
    expect(res.status()).toBe(401)
  })

  await test.step('Scraping without a token is rejected with a 401', async () => {
    const res = await request.post('/recipes/scrape', { data: { url: 'http://example.com/recipe' } })
    expect(res.status()).toBe(401)
  })
})
```

- [ ] **Step 2: Write the projects suite**

Create `checkly/__checks__/tests/projects.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { authHeaders } from './lib/auth'

let headers: Record<string, string> = {}
let projectId: string | null = null

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  headers = await authHeaders(request)
})

test.afterAll('Delete the synthetic test project if one remains', async ({ request }) => {
  if (projectId) await request.delete(`/projects/${projectId}`, { headers })
})

test('Authenticated users can create, read, update, and delete a project', async ({ request }) => {
  await test.step('Create a new project', async () => {
    const res = await request.post('/projects', {
      headers,
      data: { title: `checkly-synthetic-test-${Date.now()}`, description: 'Synthetic monitoring test — safe to delete' },
    })
    expect(res.status()).toBe(200)
    const created = await res.json()
    expect(created.id).toBeTruthy()
    projectId = created.id
  })

  await test.step('View the newly created project', async () => {
    const res = await request.get(`/projects/${projectId}`, { headers })
    expect(res.status()).toBe(200)
    expect((await res.json()).id).toBe(projectId)
  })

  await test.step('Update the project', async () => {
    const res = await request.put(`/projects/${projectId}`, {
      headers, data: { title: `checkly-synthetic-test-${Date.now()}` },
    })
    expect(res.status()).toBe(200)
  })

  const deletedId = projectId

  await test.step('Delete the project', async () => {
    const res = await request.delete(`/projects/${deletedId}`, { headers })
    expect(res.status()).toBe(204)
    projectId = null
  })

  await test.step('Confirm the project no longer exists', async () => {
    const res = await request.get(`/projects/${deletedId}`, { headers })
    expect(res.status()).toBe(404)
  })
})
```

- [ ] **Step 3: Delete the old copies**

```bash
git rm checkly/__checks__/recipes.spec.ts checkly/__checks__/project-lifecycle.spec.ts
```

- [ ] **Step 4: Run both against production (needs creds)**

Run (from `checkly/`):
```bash
set -a; source ../.env.fly.staging; set +a
npx playwright test --project=recipes --project=projects
```
Expected: `2 passed`. Both create and delete synthetic data on prod.

- [ ] **Step 5: Commit**

```bash
git add checkly/__checks__/tests/recipes.spec.ts checkly/__checks__/tests/projects.spec.ts
git commit -m "refactor(checkly): migrate write-path suites to Playwright project

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 5: Project-scoring suite (real, bounded Claude call)

The highest-value new suite. Bounds cost to one Claude call and asserts a **fresh numeric score** actually came back — necessary because `POST /scores` swallows per-definition scoring errors and returns 200 regardless.

**Files:**
- Create: `checkly/__checks__/tests/scoring.spec.ts`

**Interfaces:**
- Consumes: `authHeaders` from Task 2; Playwright project `scoring` from Task 1.
- Backend contract used:
  - `POST /score-definitions` body `{ name, dimensions: [{ name, criteria }] }` → `200` with `{ id, ... }`.
  - `POST /projects/:id/scores` body `{ 'definition-ids': [<uuid>] }` → `200`, runs one synchronous Claude call per id.
  - `GET /projects/:id/scores` → `200` with an array of latest runs, each `{ id, version, scored-at, definition: { id, name, scorer-type }, overall, dimensions }` (kebab-case; the definition id is nested at `definition.id`, `overall` is a top-level number).
  - `DELETE /score-definitions/:id`, `DELETE /projects/:id` for cleanup.

- [ ] **Step 1: Write the scoring suite**

Create `checkly/__checks__/tests/scoring.spec.ts`:

```ts
import { test, expect } from '@playwright/test'
import { authHeaders } from './lib/auth'

let headers: Record<string, string> = {}
let projectId: string | null = null
let definitionId: string | null = null

test.beforeAll('Log in as the synthetic test user', async ({ request }) => {
  headers = await authHeaders(request)
})

test.afterAll('Delete the synthetic project and score definition', async ({ request }) => {
  if (projectId)    await request.delete(`/projects/${projectId}`, { headers })
  if (definitionId) await request.delete(`/score-definitions/${definitionId}`, { headers })
})

// Suite 8 — proves the whole AI scoring chain: key valid, model reachable,
// scorer wired as `llm`, response parsed. Cost is bounded to ONE Claude call by
// using a single-dimension definition.
test('Scoring a project returns a score from Claude', async ({ request }) => {
  await test.step('Create a synthetic project', async () => {
    const res = await request.post('/projects', {
      headers,
      data: { title: `checkly-synthetic-scoring-${Date.now()}`, description: 'Synthetic monitoring — safe to delete' },
    })
    expect(res.status()).toBe(200)
    projectId = (await res.json()).id
    expect(projectId).toBeTruthy()
  })

  await test.step('Create a single-dimension score definition (bounds cost to one Claude call)', async () => {
    const res = await request.post('/score-definitions', {
      headers,
      data: { name: `checkly-synthetic-scoredef-${Date.now()}`, dimensions: [{ name: 'Clarity', criteria: 'Is the project description clear?' }] },
    })
    expect(res.status()).toBe(200)
    definitionId = (await res.json()).id
    expect(definitionId).toBeTruthy()
  })

  await test.step('Trigger scoring — makes one real Claude call', async () => {
    const res = await request.post(`/projects/${projectId}/scores`, {
      headers, data: { 'definition-ids': [definitionId] },
    })
    expect(res.status()).toBe(200)
  })

  await test.step('A fresh score from Claude comes back for the definition', async () => {
    const res = await request.get(`/projects/${projectId}/scores`, { headers })
    expect(res.status()).toBe(200)
    const runs: Array<{ definition: { id: string }, overall: number }> = await res.json()
    const run = runs.find((r) => r.definition.id === definitionId)
    expect(run, 'No score run recorded — the Claude call likely failed (POST /scores returns 200 even when scoring throws)').toBeTruthy()
    expect(typeof run!.overall, 'Score run has no numeric overall').toBe('number')
  })
})
```

- [ ] **Step 2: Run it against production (needs creds; makes a paid Claude call)**

Run (from `checkly/`):
```bash
set -a; source ../.env.fly.staging; set +a
npx playwright test --project=scoring
```
Expected: `1 passed`. If the final step fails with "No score run recorded", inspect the `POST /score-definitions` response shape (confirm `id` is top-level) and confirm production has `KALEIDOSCOPE_SCORER_TYPE=llm` and a valid `ANTHROPIC_API_KEY`.

- [ ] **Step 3: Commit**

```bash
git add checkly/__checks__/tests/scoring.spec.ts
git commit -m "feat(checkly): project-scoring suite with bounded real Claude call

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 6: Checkly wrappers + remove old constructs

Replaces the six old `.check.ts` constructs (and `config.ts`) with a single declarative `suites.check.ts` of eight `PlaywrightCheck` wrappers.

**Files:**
- Create: `checkly/__checks__/suites.check.ts`
- Delete: `checkly/__checks__/health.check.ts`, `homepage.check.ts`, `auth-boundary.check.ts`, `compositions.check.ts`, `project-lifecycle.check.ts`, `recipes.check.ts`, `config.ts`

**Interfaces:**
- Consumes: Playwright projects from Task 1; `playwright.config.ts` at `../playwright.config.ts` relative to this file.

- [ ] **Step 1: Write the wrappers**

Create `checkly/__checks__/suites.check.ts`:

```ts
import { PlaywrightCheck } from 'checkly/constructs'

// Each suite is a standard Playwright project (see ../playwright.config.ts).
// These thin wrappers are the ONLY Checkly-aware code — they schedule the
// suites and tag them by cost. `no-spend` suites make no paid external call and
// run on every branch; `spends` makes a real (paid) Claude call and runs only
// as a daily production monitor. The ephemeral runner selects `--tags no-spend`.
const SUITES: Array<{
  project: string
  name: string
  frequency: number
  locations: string[]
  tags: string[]
}> = [
  { project: 'liveness',      name: 'The server is running and reports its version.',                                 frequency: 360,  locations: ['us-east-1', 'eu-west-1'], tags: ['no-spend'] },
  { project: 'homepage',      name: 'The homepage loads and renders without client-side errors.',                     frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'auth-boundary', name: 'Protected endpoints reject unauthenticated requests with 401.',                  frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'auth0-login',   name: 'The synthetic user can obtain an Auth0 access token.',                           frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'articles',      name: 'Published articles can be listed and opened, and missing articles return 404.',  frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'recipes',       name: 'Recipes can be created, labeled, read, and deleted, and their invariants hold.', frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'projects',      name: 'Authenticated users can create, read, update, and delete a project.',            frequency: 360,  locations: ['us-east-1'],              tags: ['no-spend'] },
  { project: 'scoring',       name: 'Scoring a project returns a score from Claude.',                                 frequency: 1440, locations: ['us-east-1'],              tags: ['spends'] },
]

for (const suite of SUITES) {
  new PlaywrightCheck(`suite-${suite.project}`, {
    name: suite.name,
    playwrightConfigPath: '../playwright.config.ts',
    pwProjects: [suite.project],
    frequency: suite.frequency,
    locations: suite.locations,
    tags: suite.tags,
  })
}
```

- [ ] **Step 2: Delete the old constructs and config**

```bash
git rm checkly/__checks__/health.check.ts checkly/__checks__/homepage.check.ts \
       checkly/__checks__/auth-boundary.check.ts checkly/__checks__/compositions.check.ts \
       checkly/__checks__/project-lifecycle.check.ts checkly/__checks__/recipes.check.ts \
       checkly/__checks__/config.ts
```

- [ ] **Step 3: Validate the constructs parse and resolve to eight checks**

Run (from `checkly/`):
```bash
npx checkly test --list
```
Expected: exactly eight checks listed, named by the declarative sentences above, with tags `no-spend` (seven) and `spends` (one). No parse errors, no leftover checks from deleted constructs.

- [ ] **Step 4: Confirm the tag filter selects the seven free suites**

Run (from `checkly/`):
```bash
npx checkly test --list --tags no-spend
```
Expected: seven checks listed; the `Scoring a project returns a score from Claude.` suite is absent.

- [ ] **Step 5: Commit**

```bash
git add checkly/__checks__/suites.check.ts
git commit -m "refactor(checkly): replace per-check constructs with PlaywrightCheck wrappers

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 7: Fence the paid suite out of the ephemeral run + document

Updates the ephemeral regression runner to select `no-spend`, and records the whole scheme in the operations doc (CLAUDE.md Sharp edge #6).

**Files:**
- Modify: `scripts/ephemeral/checkly-test`
- Modify: `docs/operations.md`

- [ ] **Step 1: Add the tag filter to the ephemeral runner**

In `scripts/ephemeral/checkly-test`, change the `npx checkly test` invocation to select only the free suites. Replace:

```bash
       npx checkly test \
         --env ENVIRONMENT_URL="$BASE_URL" \
```
with:
```bash
       npx checkly test \
         --tags no-spend \
         --env ENVIRONMENT_URL="$BASE_URL" \
```

- [ ] **Step 2: Update the runner's header comment**

In the same file, update the top-of-file comment that describes what it runs so it states that only `no-spend` suites run here and that the paid `spends` (scoring) suite is deliberately excluded from per-branch deploys. Replace the line describing the run (currently "runs a one-off `checkly test` session in Checkly's cloud runners") with:

```bash
# passing ENVIRONMENT_URL (see checkly/playwright.config.ts) and runs a one-off
# `checkly test --tags no-spend` session in Checkly's cloud runners. The paid
# `spends` suite (project scoring → a real Claude call) is intentionally NOT run
# on branch deploys; it runs only as a daily production monitor.
```

- [ ] **Step 3: Document the suite in operations.md**

Append this section to `docs/operations.md`:

```markdown
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
# write / login / scoring suites need Auth0 creds; scoring makes a paid Claude call
set -a; source ../.env.fly.staging; set +a
ENVIRONMENT_URL=https://kal-eph-<slug>.fly.dev npx playwright test --project=scoring
```

**Cost tags.** Suites carry `no-spend` (no paid external call) or `spends` (one
real, paid Claude call). `checkly test --tags` is include-only.

| Context | Command | Runs |
|---|---|---|
| Ephemeral `up` | `checkly test --tags no-spend` (in `scripts/ephemeral/checkly-test`) | 7 free suites |
| On-demand LLM check | `checkly test --tags spends --env ENVIRONMENT_URL=<url>` | scoring only (paid) |
| Production monitors | `checkly deploy` | all 8; scoring daily, rest every 6h |

**Deployed monitors need these Checkly environment variables** (set once in the
Checkly account, not committed): `AUTH0_CLIENT_ID`, `AUTH0_CLIENT_SECRET`.
`ENVIRONMENT_URL` is omitted in production so checks default to the live site.
```

- [ ] **Step 4: Verify the script still parses**

Run:
```bash
bash -n scripts/ephemeral/checkly-test && echo "syntax OK"
```
Expected: `syntax OK`.

- [ ] **Step 5: Commit**

```bash
git add scripts/ephemeral/checkly-test docs/operations.md
git commit -m "chore(checkly): exclude paid scoring suite from ephemeral run; document suite

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Task 8: Deploy the monitors (ops step — requires Checkly account)

Registers the eight suites as scheduled monitors. Requires `CHECKLY_API_KEY` and `CHECKLY_ACCOUNT_ID`, and the two Auth0 env vars set in the Checkly account. This step talks to a live third-party account; run it deliberately.

**Files:** none (deploy action).

- [ ] **Step 1: Ensure the Auth0 env vars exist in Checkly**

Set them once (from `checkly/`, with `CHECKLY_API_KEY`/`CHECKLY_ACCOUNT_ID` in the environment):
```bash
npx checkly env add AUTH0_CLIENT_ID "<value>"
npx checkly env add AUTH0_CLIENT_SECRET "<value>"
```
Expected: both listed by `npx checkly env ls`.

- [ ] **Step 2: Preview the deploy**

Run (from `checkly/`):
```bash
npx checkly deploy --preview
```
Expected: shows eight checks to create/update with the correct names, tags, and frequencies (scoring at 1440, rest at 360).

- [ ] **Step 3: Deploy**

Run (from `checkly/`):
```bash
npx checkly deploy
```
Expected: deploy succeeds; the Checkly dashboard shows eight monitors, the `spends` one running daily.

- [ ] **Step 4: Smoke the paid suite once, on demand**

Run (from `checkly/`, creds loaded):
```bash
npx checkly test --tags spends --env ENVIRONMENT_URL=https://sahiltalkingcents.com
```
Expected: the scoring suite passes against production (one real Claude call).

---

## Self-Review

**Spec coverage** (against `DESIGN.md`):
- §3 suites 1–8 → Tasks 1, 3, 3, 2, 3, 4, 4, 5 (all eight implemented, names copied verbatim).
- §4 architecture (Playwright project, `suites.check.ts`, `lib/auth.ts`) → Tasks 1, 2, 6.
- §5 Playwright-first / `ApiCheck` → Playwright `request` → Task 1 (liveness replaces the native ApiCheck).
- §6 environments/tags/`--tags no-spend` ephemeral change → Tasks 6, 7.
- §7 operations doc → Task 7.
- §6 production deploy + Checkly env vars → Task 8.
- §2.2 isolate Auth0 as its own suite → Task 2.

**Placeholder scan:** no TBD/TODO; every code step shows complete file content; the one `<value>`/`<slug>` tokens are genuine user-supplied secrets/identifiers, not omitted logic.

**Type consistency:** `getAccessToken`/`authHeaders` (Task 2) are consumed with matching signatures in Tasks 4–5. Playwright project names in `playwright.config.ts` (Task 1) match `pwProjects` values in `suites.check.ts` (Task 6) and every `--project=` command. Response fields (`id`, `article-url`, `score-definition-id`, `overall`, `recipe-url`) match the verified backend contracts.
```
