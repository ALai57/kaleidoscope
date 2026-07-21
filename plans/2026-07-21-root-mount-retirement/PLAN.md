# Backend Root-Mount Retirement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Retire the legacy root mounts of the JSON API resource routes so those paths (`/recipes`, `/compositions`, `/articles`, …) become frontend page routes served by the SPA shell, while the API lives only under `/api/v1`. This is what makes `andrewslai.com/recipes` render the app instead of returning JSON.

**Architecture:** Every resource route group is currently dual-mounted at both its root path and under `/api/v1`, with derived twin ACL rules. This plan (1) first repoints the existing HTTP test suite off the root paths and onto `/api/v1` — a safe refactor while dual-mount is still live — then (2) drops the root mount, (3) deletes the now-dead root resource ACL rules, (4) repoints the Checkly monitors, and (5) updates the operations doc. After retirement, an unmatched root GET like `/recipes` falls through the reitit not-found handler to the SPA shell (already implemented); non-GET or reserved-prefix misses still 404.

**Tech Stack:** Clojure, reitit-ring, buddy-auth access-rules, Kaocha + matcher-combinators + ring-mock; Checkly (Monitoring-as-Code) Playwright suites.

## Global Constraints

- **HARD GATE — do not start until the frontend `/api/v1` repoint (`kaleidoscope-ui` `plans/2026-07-21-api-v1-repoint/PLAN.md`) has SHIPPED AND the new build is DEPLOYED/live.** Retiring root mounts before the deployed frontend stops calling them breaks production: a live-but-stale UI build calls `/recipes` etc. and would receive the SPA shell (HTML) instead of JSON.
- **Photos, media, admin, self-versioned, and infra routes stay at root.** Only the 20 groups in `api-route-groups` lose their root mount. NOT retired: `reitit-photos-routes` (`/v2/photos`), `/media/*`, `reitit-admin-routes` (`/admin`), `/ping`, `/`, `/index.html`, `/openapi.json`, and the self-versioned `/check-domain`, `/v1/payments`, `/registration`.
- **The completeness check is the full suite.** After Task 2 retires the root mount, ANY resource test still pointing at a root path fails. So "full suite green after Task 2" is the authoritative proof that every test was repointed — lean on it rather than trying to eyeball every call site.
- **The `/api/v1` mount and its twin ACL rules are the surviving address space — never weaken their authorization.** GET-public / writer-gated / admin-gated semantics must stay identical to what root had.
- **Fail-closed ACL is sacred.** The catch-all `{:pattern #"^/.*" :handler (constantly false)}` MUST stay strictly last. `access-control-list-fails-closed-test` must keep passing untouched.
- **Reserved-prefix contract is unchanged.** `reserved-backend-prefixes` stays exactly as-is: `["/api/v1" "/api-docs" "/assets" "/static" "/media" "/v2/photos" "/openapi.json" "/favicon.ico" "/ping" "/index.html"]`.
- **Test debugging discipline (CLAUDE.md):** use `task test:summary` / `./bin/test --focus`; save full output to `$SCRATCHPAD` and grep — never pipe full suite output into the conversation.

---

### Task 1: Repoint the existing HTTP test suite to `/api/v1`

This is a behavior-preserving refactor: while dual-mount is still live, both `/recipes` and `/api/v1/recipes` work, so moving the tests to `/api/v1` keeps them green and pre-stages them for retirement. Do this first so Task 2's code change lands against an already-migrated suite.

**Files:**
- Modify: `test/kaleidoscope/http_api/kaleidoscope_test.clj` (shared helpers + every domain resource deftest)

**Interfaces:**
- Consumes: the live dual-mounted `/api/v1` surface.
- Produces: the domain test suite issues all resource requests under `/api/v1`; no domain test depends on a root resource mount.

**Resource path prefixes to repoint** (prepend `/api/v1`): `/albums`, `/articles`, `/article-audiences`, `/branches`, `/compositions`, `/groups`, `/interests`, `/projects`, `/projects-portfolio`, `/score-definitions`, `/agents`, `/tasks`, `/workflows`, `/workspace-roots`, `/recipes`, `/recipe-labels`, `/recipe-label-groups`, `/recipe-audiences`, `/themes`.

**Paths to LEAVE untouched** (not resource routes / stay at root): `/ping`, `/`, `/index.html`, `/openapi.json`, `/admin`, `/v2/photos*`, `/media*`, `/v1/payments`, `/check-domain`, `/registration`, `/static*`, `/favicon.ico`, and the SPA page paths in the fallback tests (`/library/*`, `/about`). Do NOT touch the meta/ACL tests in this task — `access-rule-configuration-test`, `access-control-list-fails-closed-test`, `api-v1-dual-mount-test`, `api-v1-acl-*` are handled in Tasks 2–3.

- [ ] **Step 1: Rewrite the six shared request helpers to emit `/api/v1` paths**

These helpers (lines ~382–430) back the articles/branches/compositions tests. Prefix the path segment in each:

```clojure
(defn create-branch
  ([article]
   (create-branch article ""))
  ([article host]
   (-> (mock/request :post (str host "/api/v1/branches"))
       (mock/json-body article)
       (mock/header "Authorization" "Bearer x"))))

(defn get-branches
  ([]
   (get-branches nil))
  ([query]
   (get-branches query ""))
  ([query host]
   (cond-> (mock/request :get (str host "/api/v1/branches"))
     true  (mock/header "Authorization" "Bearer x")
     query (mock/query-string query))))

(defn create-version
  ([article-url branch version]
   (create-version "localhost" article-url branch version))
  ([host article-url branch version]
   (-> (mock/request :post (format "%s/api/v1/articles/%s/branches/%s/versions"
                                   host
                                   article-url
                                   branch))
       (mock/json-body version)
       (mock/header "Authorization" "Bearer x"))))

(defn get-version
  ([branch-id]
   (get-version "localhost" branch-id))
  ([host branch-id]
   (-> (mock/request :get (format "%s/api/v1/branches/%s/versions" host branch-id))
       (mock/header "Authorization" "Bearer x"))))

(defn get-composition
  [article-url]
  (mock/request :get (format "/api/v1/compositions/%s" article-url)))

(defn publish-branch
  ([article-url branch-name]
   (publish-branch "localhost" article-url branch-name))
  ([host article-url branch-name]
   (-> (mock/request :put (format "%s/api/v1/articles/%s/branches/%s/publish"
                                  host
                                  article-url
                                  branch-name))
       (mock/header "Authorization" "Bearer x"))))
```

- [ ] **Step 2: Repoint the `are`-table endpoint strings**

In `articles-routes-test` (~452–454), prefix each endpoint:

```clojure
    "/api/v1/articles"                  {:status 200 :body (has-count 4)}
    "/api/v1/articles/my-first-article" {:status 200 :body (malli-matcher models.articles/GetArticleResponse)}
    "/api/v1/articles/does-not-exist"   {:status 404}
```

In `published-article-retrieval-test` (~477–479):

```clojure
    "/api/v1/compositions"                  {:status 200 :body (has-count 4)}
    "/api/v1/compositions/my-first-article" {:status 200 :body (malli-matcher models.articles/GetCompositionResponse)}
    "/api/v1/compositions/does-not-exist"   {:status 404}
```

- [ ] **Step 3: Repoint the inline resource requests in the remaining domain deftests**

For each request path in the deftests listed below, prepend `/api/v1` to the resource path segment (after the `http(s)://host` prefix, or at the start for host-less paths). The transformation is uniform — e.g. `"https://andrewslai.com/groups"` → `"https://andrewslai.com/api/v1/groups"`, `(format "https://andrewslai.com/compositions/%s" x)` → `(format "https://andrewslai.com/api/v1/compositions/%s" x)`, `"/projects-portfolio"` → `"/api/v1/projects-portfolio"`.

Deftests to repoint (all use only the prefixes listed above):
- `fixed-resolver-scopes-content-test` — `/compositions`
- `publish-branch-test` — inline `(format "https://andrewslai.com/compositions/%s" …)` read-backs (helpers cover the rest)
- `get-versions-test` — inline `/compositions/%s` read-backs (helpers cover the rest)
- `themes-get-does-not-leak-across-tenants-test` — `/themes` (both the `andrewslai.com` and `caheriaguilar.com` requests)
- `get-versions-does-not-leak-across-tenants-test` — helpers cover it; check for any inline `/compositions`/`/branches`
- `portfolio-test` — `/projects-portfolio`
- `create-and-remove-group-test` — `/groups`, `/groups/%s`, `/groups/%s/members`, `/groups/%s/members/%s`
- `retrieve-group-test` — `/groups*`
- `albums-test`, `album-contents-test`, `contents-retrieval-test`, `albums-auth-test` — `/albums`, `/albums/%s`, `/albums/%s/contents`, `/albums/%s/contents/%s`, `/albums/-/contents`
- `audiences-test` — `/article-audiences`, `/article-audiences/%s`, `/article-audiences?article-id=…`, and its `/groups` setup request
- `themes-test` — `/themes`, `/themes/%s`, `/themes?id=%s`
- `auth-runs-before-coercion-test` — `/workflows`

Also repoint the group/audience setup requests embedded in `create-branch-happy-path-test` / `publish-branch-test` region if any hit `/groups` or `/article-audiences`.

Leave these deftests entirely untouched (they target non-retired routes): `ping-test`, `home-test`, `swagger-test`, `admin-routes-test`, `static-chrome-serves-from-the-shared-client-store-test`, `tenant-resolver-*`, `payments-test`, `check-domain`, `payments-validation-test`, `payments-rate-limit-test`, `check-domain-validation-test`, `check-domain-rate-limit-test`, `photos-test`, `index.html-test`.

- [ ] **Step 4: Run the repointed domain suite — it must stay green (dual-mount)**

Run:
```bash
task test:summary 2>&1 > $SCRATCHPAD/t1.log; grep -E "tests,|assertions|[0-9]+ failures|[0-9]+ errors" $SCRATCHPAD/t1.log; grep -E "^(FAIL|ERROR) in" $SCRATCHPAD/t1.log
```
Expected: 0 failures, 0 errors. (Both root and `/api/v1` still resolve, so a fully-repointed suite is green; a lingering `FAIL` here means a helper/table typo, not retirement — fix it.)

- [ ] **Step 5: Commit**

```bash
git add test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "test(routing): repoint HTTP resource tests to /api/v1

- shared branch/version/composition helpers and every domain resource
  deftest now issue requests under /api/v1
- behavior-preserving under the current dual-mount; pre-stages the suite
  for root-mount retirement
- leaves infra/photos/media/admin and self-versioned payment/domain
  tests at their root paths"
```

---

### Task 2: Drop the root resource-route mount

**Files:**
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj` (`kaleidoscope-app`, the `ring/router` call ~lines 268–284)
- Test: `test/kaleidoscope/http_api/kaleidoscope_test.clj` (`api-v1-dual-mount-test` → rewrite; `spa-fallback-serves-shell-for-page-paths-test` → extend; `access-rule-configuration-test` → repoint its resource rows)

**Interfaces:**
- Consumes: `api-route-groups` (unchanged 20-group vector), `spa-shell-request?`, `reserved-backend-path?`, the SPA-shell not-found handler (all already present).
- Produces: resource routes reachable ONLY under `/api/v1`; every unmatched root GET that is not a reserved prefix serves the SPA shell.

- [ ] **Step 1: Rewrite `api-v1-dual-mount-test` into a root-serves-shell test**

Replace the whole `api-v1-dual-mount-test` deftest with:

```clojure
(deftest recipes-root-serves-shell-not-api-test
  ;; After root-mount retirement, /recipes is a FRONTEND page (SPA shell),
  ;; not the JSON API. The API lives only under /api/v1.
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (testing "GET /recipes serves the SPA shell (HTML), not the recipes API"
      (is (match? {:status  200
                   :headers {"Content-Type" #"text/html"}
                   :body    "<div>Hello</div>"}
                  (app (mock/request :get "https://andrewslai.com/recipes")))))
    (testing "GET /api/v1/recipes still reaches the public JSON handler"
      (is (match? {:status 200 :body sequential?}
                  (app (mock/request :get "https://andrewslai.com/api/v1/recipes")))))
    (testing "GET /api/v1/projects-portfolio is public"
      (is (match? {:status 200}
                  (app (mock/request :get "https://andrewslai.com/api/v1/projects-portfolio")))))
    (testing "POST /api/v1/recipes still requires a writer (401 unauthenticated)"
      (is (match? {:status 401}
                  (app (-> (mock/request :post "https://andrewslai.com/api/v1/recipes")
                           (mock/json-body {}))))))))
```

- [ ] **Step 2: Extend `spa-fallback-serves-shell-for-page-paths-test`**

Add a case after the "bare unknown page path" block (~line 116) asserting a retired resource path now deep-links to the shell:

```clojure
    (testing "a retired API path (/recipes) now serves the SPA shell"
      (is (match? {:status 200 :body "<div>Hello</div>"}
                  (app (mock/request :get "https://andrewslai.com/recipes")))))
```

- [ ] **Step 3: Repoint the resource rows in `access-rule-configuration-test`**

Change the four resource rows (leave `/ping`, `/`, `/openapi.json`, `/admin`, and both `/v2/photos` rows exactly as they are):

```clojure
    "GET `/api/v1/projects-portfolio` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/api/v1/projects-portfolio")

    "GET `/api/v1/compositions` is publicly accessible"
    {:status 200} (mock/request :get "https://andrewslai.com/api/v1/compositions")

    "GET `/api/v1/articles/does-not-exist` is not publicly accessible"
    {:status 401} (mock/request :get "https://andrewslai.com/api/v1/articles/does-not-exist")

    "POST `/api/v1/articles/new-article` is not publicly accessible"
    {:status 401} (mock/request :post "https://andrewslai.com/api/v1/articles/new-article/branches/new-branch/versions")
```

- [ ] **Step 4: Verify the routing tests FAIL against the current dual-mount**

Run:
```bash
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/recipes-root-serves-shell-not-api-test 2>&1 > $SCRATCHPAD/t2.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t2.log
```
Expected: FAIL — `GET /recipes` still returns the JSON API (`sequential?` / `application/json`), not the shell.

- [ ] **Step 5: Drop the root mount in `kaleidoscope-app`**

Change the `ring/router` first argument so `api-route-groups` mounts ONLY under `/api/v1`:

```clojure
      (ring/router
       (into [;; Administrative/helpers + already-namespaced photos (root only)
              reitit-ping-routes
              reitit-openapi-routes
              reitit-index-routes
              reitit-admin-routes
              reitit-photos-routes]
             ;; Resource groups now live ONLY under /api/v1. Their legacy root
             ;; mounts are retired — an unmatched root GET (e.g. /recipes) falls
             ;; through to the SPA shell (see the default handler below). Drop
             ;; :no-doc so the /api/v1 resources appear in the OpenAPI spec.
             [(into ["/api/v1"] api-route-groups)])
       ;; reitit-stripe-routes and reitit-registration-routes intentionally
       ;; not mounted yet - not ready for production.
       reitit-config)
```

- [ ] **Step 6: Run the routing tests AND the full suite**

Run:
```bash
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/recipes-root-serves-shell-not-api-test 2>&1 > $SCRATCHPAD/t2b.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t2b.log
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/spa-fallback-serves-shell-for-page-paths-test 2>&1 >> $SCRATCHPAD/t2b.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t2b.log
task test:summary 2>&1 > $SCRATCHPAD/t2full.log; grep -E "tests,|assertions|[0-9]+ failures|[0-9]+ errors" $SCRATCHPAD/t2full.log; grep -E "^(FAIL|ERROR) in" $SCRATCHPAD/t2full.log
```
Expected: the focused routing tests PASS, and `task test:summary` reports **0 failures, 0 errors**. Any failure in the full run means a Task-1 repoint was missed (that test still hits a now-dead root path) — fix the offending test's path here and re-run.

- [ ] **Step 7: Commit**

```bash
git add src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "feat(routing): retire root mounts of the JSON API resource routes

- api-route-groups now mount only under /api/v1 (drop the bare root
  mount and its :no-doc marker so /api/v1 shows in OpenAPI)
- /recipes and the other former API paths now fall through to the SPA
  shell; only /api/v1/* serves JSON
- rewrite the dual-mount test into a root-serves-shell test; extend the
  SPA-fallback test; repoint the resource rows in the ACL config test"
```

---

### Task 3: Remove the dead root resource ACL rules

**Files:**
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj` (`KALEIDOSCOPE-ACCESS-CONTROL-LIST`, ~lines 80–125)
- Test: `test/kaleidoscope/http_api/kaleidoscope_test.clj` (`api-v1-acl-twin-coverage-test` → add root-absent assertion + rename; `api-v1-acl-authorizes-like-root-test` → drop root rows + rename)

**Interfaces:**
- Consumes: `api-resource-access-rules` (the def stays — still the source the twins are derived from), `with-api-v1-prefix`.
- Produces: the ACL contains ONLY the `/api/v1` twins for resources (plus the untouched infra/photos/media root rules and the public block). A future re-added root resource route hits the fail-closed catch-all instead of a stale public rule.

- [ ] **Step 1: Update the ACL unit tests to the retired invariant**

Replace `api-v1-acl-twin-coverage-test`:

```clojure
(deftest api-v1-acl-twins-present-and-root-rules-absent-test
  ;; Post-retirement invariant: every API resource rule is present ONLY as its
  ;; /api/v1 twin. The root form must be gone — otherwise a future re-added root
  ;; resource route would inherit a stale, still-public ACL rule instead of the
  ;; fail-closed catch-all.
  (let [triple  (juxt (comp str :pattern) :request-method :handler)
        present (set (map triple kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST))]
    (doseq [rule kaleidoscope/api-resource-access-rules]
      (let [twin (kaleidoscope/with-api-v1-prefix rule)]
        (is (contains? present (triple twin))
            (str "missing /api/v1 twin for " (:pattern rule)))
        (is (not (contains? present (triple rule)))
            (str "root resource rule should be retired: " (:pattern rule)))))))
```

Replace `api-v1-acl-authorizes-like-root-test`:

```clojure
(deftest api-v1-acl-authorizes-resources-test
  ;; Tested at the auth-stack level (below the router, like the fails-closed
  ;; test) so it doesn't depend on route mounting.
  (let [rules   kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST
        stack   (mw/auth-stack (bb/authenticated-backend {:realm_access {:roles []}}) rules)
        handler (reduce (fn [h a-mw] (a-mw h))
                        (fn [_req] {:status 200 :body "reached"})
                        (reverse stack))
        req     (fn [method uri] {:uri uri :request-method method :headers {}})]
    (testing "GET /api/v1/recipes is public"
      (is (= 200 (:status (handler (req :get "/api/v1/recipes"))))))
    (testing "POST /api/v1/recipes requires a writer"
      (is (= 401 (:status (handler (req :post "/api/v1/recipes"))))))
    (testing "a root resource path now matches the fail-closed catch-all (401)"
      (is (= 401 (:status (handler (req :post "/recipes"))))))))
```

- [ ] **Step 2: Verify these tests FAIL against the current ACL**

Run:
```bash
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-twins-present-and-root-rules-absent-test 2>&1 > $SCRATCHPAD/t3.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t3.log
```
Expected: FAIL — root resource rules are still present, so "root resource rule should be retired" fails.

- [ ] **Step 3: Remove the root resource rules from the ACL**

In `KALEIDOSCOPE-ACCESS-CONTROL-LIST`, drop the bare `api-resource-access-rules` entry from the `concat` (and its "at their legacy root paths …" comment), keeping the derived twins. The infra/photos/media root block and the public block are unchanged. The middle of the `concat` becomes:

```clojure
    ;; API resource rules exist ONLY as their derived /api/v1 twins — the legacy
    ;; root mounts were retired, so root resource paths intentionally fall to the
    ;; fail-closed catch-all below.
    (map with-api-v1-prefix api-resource-access-rules)
```

- [ ] **Step 4: Verify the ACL tests PASS (and fails-closed stays green)**

Run:
```bash
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-twins-present-and-root-rules-absent-test 2>&1 > $SCRATCHPAD/t3b.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t3b.log
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-authorizes-resources-test 2>&1 >> $SCRATCHPAD/t3b.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t3b.log
./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/access-control-list-fails-closed-test 2>&1 >> $SCRATCHPAD/t3b.log; grep -E "^(FAIL|ERROR) in|tests,|assertions" $SCRATCHPAD/t3b.log
```
Expected: all three PASS. (`access-control-list-fails-closed-test` is unchanged and proves the catch-all still rejects unregistered URIs — including, now, root resource paths.)

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "feat(routing): remove retired root resource ACL rules

- KALEIDOSCOPE-ACCESS-CONTROL-LIST keeps only the /api/v1 resource twins;
  the root resource rules are dropped now that root mounts are gone
- root resource paths fall to the fail-closed catch-all, so a future
  re-added root route can't inherit a stale public rule
- twin-coverage test now also asserts root rules are absent; the
  authorizes-like-root test drops its root-path block"
```

---

### Task 4: Repoint the Checkly synthetic monitors to `/api/v1`

**Files:**
- Modify: `checkly/__checks__/tests/auth-boundary.spec.ts`
- Modify: `checkly/__checks__/tests/articles.spec.ts`
- Modify: `checkly/__checks__/tests/recipes.spec.ts`
- Modify: `checkly/__checks__/tests/projects.spec.ts`

**Interfaces:**
- Consumes: the deployed `/api/v1` API surface (already live via dual-mount).
- Produces: monitors exercising the surviving `/api/v1` address space. `liveness.spec.ts` (`/ping`), `homepage.spec.ts` (`/`), `auth0-login.spec.ts`, and `scoring.spec.ts` are NOT repointed.

**Why:** after Tasks 2–3 deploy, `GET /articles` (auth-boundary) returns the SPA shell (200) instead of 401, and the articles/recipes/projects flows hit HTML/404. `/api/v1` already works in production, so repointing is safe and required.

- [ ] **Step 1: Repoint `auth-boundary.spec.ts`**

```ts
    { method: 'get',  path: '/api/v1/articles',  description: 'Listing articles requires authentication' },
    { method: 'get',  path: '/api/v1/projects',  description: 'Listing projects requires authentication' },
    { method: 'post', path: '/api/v1/workflows', description: 'Starting a workflow requires authentication' },
    { method: 'get',  path: '/api/v1/agents',    description: 'Listing agents requires authentication' },
```

- [ ] **Step 2: Repoint `articles.spec.ts`**

```ts
    const listRes = await request.get('/api/v1/compositions')
    // ...
    const articleRes = await request.get(`/api/v1/compositions/${encodeURIComponent(slug)}`)
    // ...
    const missingRes = await request.get('/api/v1/compositions/checkly-nonexistent-xyz')
```

- [ ] **Step 3: Repoint `recipes.spec.ts`**

Prefix every `/recipes*`, `/recipe-labels*`, and `/recipe-label-groups*` request path (~15 occurrences incl. `/recipes/scrape`) with `/api/v1`. Then verify none are missed:

```bash
grep -nE "request\.(get|post|put|delete)\(['\"\`]/recipe" checkly/__checks__/tests/recipes.spec.ts
```
Expected: no matches.

- [ ] **Step 4: Repoint `projects.spec.ts`**

Prefix every `/projects*` request path with `/api/v1` (create/read/update/delete + the post-delete 404 read-back).

- [ ] **Step 5: Confirm no root resource paths remain in the repointed suites**

```bash
grep -nE "request\.(get|post|put|delete)\(['\"\`]/(articles|projects|workflows|agents|compositions|recipe)" \
  checkly/__checks__/tests/auth-boundary.spec.ts \
  checkly/__checks__/tests/articles.spec.ts \
  checkly/__checks__/tests/recipes.spec.ts \
  checkly/__checks__/tests/projects.spec.ts
```
Expected: no matches (every such path is now `/api/v1/…`). Note `auth-boundary.spec.ts` stores paths in a `path:` field, not a `request.<verb>(` call, so also eyeball that file.

- [ ] **Step 6: Commit**

```bash
git add checkly/__checks__/tests/auth-boundary.spec.ts \
        checkly/__checks__/tests/articles.spec.ts \
        checkly/__checks__/tests/recipes.spec.ts \
        checkly/__checks__/tests/projects.spec.ts
git commit -m "test(checkly): repoint resource-route monitors to /api/v1

- auth-boundary, articles, recipes, and projects suites now target the
  surviving /api/v1 API surface
- liveness (/ping), homepage (/), auth0-login, and scoring are unchanged"
```

---

### Task 5: Update the operations doc

**Files:**
- Modify: `docs/operations.md` (`## URL structure (API vs pages)` transition note, ~lines 86–95)

**Interfaces:**
- Consumes: nothing.
- Produces: the doc reflects the retired end state.

- [ ] **Step 1: Replace the dual-mount transition note with the retired end state**

Read the current `## URL structure (API vs pages)` section, then replace the "**Transition state:** … dual-mounted …" paragraph AND the "Until then, the Checkly checks below intentionally still target the root paths." sentence with:

```markdown
**Resolved (2026-07-21):** the legacy root mounts have been **retired**. JSON
API resource routes are served **only** under `/api/v1/*`; the matching root
paths (`/recipes`, `/compositions`, …) are now frontend pages served by the SPA
shell. `/v2/photos/*` (photos), `/media/*` (article assets), `/admin`, and infra
(`/ping`, `/`, `/openapi.json`) remain at root. The Checkly suites below target
`/api/v1`.
```

- [ ] **Step 2: Verify the doc no longer claims dual-mounting**

```bash
grep -nE "dual-mount|still target the root|intentionally still" docs/operations.md
```
Expected: no matches.

- [ ] **Step 3: Commit**

```bash
git add docs/operations.md
git commit -m "docs(operations): record root-mount retirement in URL structure

- resource routes are /api/v1-only; root paths are SPA pages
- Checkly suites target /api/v1; photos/media/admin/infra stay at root"
```

---

### Task 6: Full-suite gate

**Files:** none (verification only).

- [ ] **Step 1: Run the full backend suite**

```bash
task test:summary 2>&1 > $SCRATCHPAD/full.log; grep -E "tests,|assertions|[0-9]+ failures|[0-9]+ errors" $SCRATCHPAD/full.log; grep -E "^(FAIL|ERROR) in" $SCRATCHPAD/full.log
```
Expected: 0 failures, 0 errors. On any failure: `grep -A4 "^FAIL" $SCRATCHPAD/full.log` and fix (a lingering root-path test).

- [ ] **Step 2: Confirm `/api/v1` is documented in OpenAPI**

The `:no-doc true` removal (Task 2) means `/api/v1` resources now appear in the spec. `swagger-test` runs in Step 1; no separate action unless it failed.

- [ ] **Step 3: No commit** — verification only.

---

## Post-plan handoff & deploy sequencing

1. Merge only after the frontend `/api/v1` repoint build is **deployed and live** (Global Constraints hard gate).
2. Deploy carries the backend retirement AND the repointed Checkly suites together (`task deploy`; Checkly suites run on their next scheduled run / CI push).
3. Post-deploy production checks: `GET https://andrewslai.com/recipes` → SPA shell (HTML, 200); `GET https://andrewslai.com/api/v1/recipes` → JSON; the auth-boundary monitor now targets `/api/v1/articles` and still gets 401.
4. **Branch note:** the main `kaleidoscope` checkout may be on an unrelated feature branch. Create this plan's implementation branch off `master` (not the current working branch) before starting Task 1.
```
