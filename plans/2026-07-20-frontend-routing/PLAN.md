# Frontend Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the JSON API its own `/api/v1` URL namespace (dual-mounted alongside the existing root paths) and harden the SPA fallback so any non-reserved path deep-links to the app shell while genuine misses still 404 — so a browser navigating to a page URL renders the app, not a backend JSON response.

**Architecture:** Three focused backend changes in `http_api/kaleidoscope.clj`, all behavior-preserving for existing (root) callers: (1) restructure the access-control list so every API-resource rule's `/api/v1` twin is *derived*, not hand-written, closing the silent-auth-hole risk; (2) mount the API route groups a second time under an `/api/v1` context; (3) replace the "serve `index.html` for everything unmatched" default handler with a reserved-prefix discriminator. The collision fix for shadowed paths (`/recipes`) lands later, when the root mounts are retired after `kaleidoscope-ui` cuts over — that retirement and the frontend router are **deferred, gated tasks** at the end.

**Tech Stack:** Clojure, reitit-ring router, buddy-auth access-rules, Malli, Kaocha + matcher-combinators + ring-mock, embedded H2, in-memory filesystem adapter.

## Global Constraints

- **3-layer separation** — all changes here live in `http_api/`; do not touch `api/` or `persistence/`.
- **Every feature needs automated tests** (CLAUDE.md sharp edge #4). Each task below ends with passing tests.
- **Fail-closed ACL is load-bearing** — the catch-all `{:pattern #"^/.*" :handler (constantly false)}` must stay strictly last in `KALEIDOSCOPE-ACCESS-CONTROL-LIST`. A missed `/api/v1` twin is a silent, total data exposure.
- **buddy-auth `:pattern` matching is whole-string** (`re-matches`), so rules are mutually exclusive by prefix except where noted; preserve the relative order of overlapping rules (`^/v2/photos` vs `^/v2/photos/.*`; `^/projects-portfolio` vs `^/projects.*`).
- **API prefix is exactly `/api/v1`.** Pages own the root; reserved backend prefixes are `/api/v1`, `/api-docs`, `/assets`, `/static`, `/media`, `/v2/photos`, `/openapi.json`, `/favicon.ico`, `/ping`.
- **Zero behavior change at root** until the deferred retirement task. Both address spaces resolve to the same handlers during the transition.
- **Test debugging:** use `task test:summary` / `--focus`; never pipe full suite output (CLAUDE.md).

## Spec reference

`plans/2026-07-20-frontend-routing/DESIGN.md`.

## File map

| File | Change |
|---|---|
| `src/kaleidoscope/http_api/kaleidoscope.clj` | Restructure ACL (Task 1); extract `api-route-groups` + dual-mount (Task 2); rewrite default handler (Task 3) |
| `test/kaleidoscope/http_api/kaleidoscope_test.clj` | Add twin-coverage + auth parity tests (Task 1); dual-mount parity test (Task 2); SPA-fallback tests (Task 3) |
| `docs/operations.md` | Add the URL-structure / `/api/v1` subsection (Task 4) |

---

### Task 1: Derive `/api/v1` ACL twins from the resource rules

Restructure the flat `KALEIDOSCOPE-ACCESS-CONTROL-LIST` into three segments so the `/api/v1` authorization rules are *computed* from the root resource rules and can never drift out of sync.

**Files:**
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj:35-124` (the whole `KALEIDOSCOPE-ACCESS-CONTROL-LIST` def)
- Test: `test/kaleidoscope/http_api/kaleidoscope_test.clj`

**Interfaces:**
- Produces: `api-resource-access-rules` (vec of buddy rule maps — the resource rules that get twinned), `with-api-v1-prefix` (fn: rule → rule with `:pattern` rewritten `^/x…` → `^/api/v1/x…`), and the rebuilt `KALEIDOSCOPE-ACCESS-CONTROL-LIST` (same public var name, now including the derived twins). Task 2's `/api/v1` mount depends on these twins existing.

- [ ] **Step 1: Write the failing tests**

Add to `test/kaleidoscope/http_api/kaleidoscope_test.clj` (near `access-control-list-fails-closed-test`, which already requires `mw` and `bb`):

```clojure
(deftest api-v1-acl-twin-coverage-test
  ;; Every API resource rule must have an /api/v1 twin present in the real ACL.
  ;; The twins are derived (not hand-written), so this is a guard against a
  ;; future edit that adds a root resource rule but forgets its twin — which
  ;; would 401 that route under /api/v1 (fail-closed, but broken).
  (let [present (set (map (juxt (comp str :pattern) :request-method :handler)
                          kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST))]
    (doseq [rule kaleidoscope/api-resource-access-rules]
      (let [twin (kaleidoscope/with-api-v1-prefix rule)]
        (is (contains? present ((juxt (comp str :pattern) :request-method :handler) twin))
            (str "missing /api/v1 twin for " (:pattern rule)))))))

(deftest api-v1-acl-authorizes-like-root-test
  ;; Tested at the auth-stack level (below the router, like the fails-closed
  ;; test) so it doesn't depend on Task 2's route mounting.
  (let [rules   kaleidoscope/KALEIDOSCOPE-ACCESS-CONTROL-LIST
        stack   (mw/auth-stack (bb/authenticated-backend {:realm_access {:roles []}}) rules)
        handler (reduce (fn [h a-mw] (a-mw h))
                        (fn [_req] {:status 200 :body "reached"})
                        (reverse stack))
        req     (fn [method uri] {:uri uri :request-method method :headers {}})]
    (testing "GET /api/v1/recipes is public, like GET /recipes"
      (is (= 200 (:status (handler (req :get "/api/v1/recipes"))))))
    (testing "POST /api/v1/recipes requires a writer, like POST /recipes"
      (is (= 401 (:status (handler (req :post "/api/v1/recipes"))))))
    (testing "a root resource path is unchanged"
      (is (= 200 (:status (handler (req :get "/recipes")))))
      (is (= 401 (:status (handler (req :post "/recipes"))))))))
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-twin-coverage-test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-authorizes-like-root-test`
Expected: FAIL — `api-resource-access-rules` / `with-api-v1-prefix` are unresolved symbols.

- [ ] **Step 3: Restructure the ACL**

In `src/kaleidoscope/http_api/kaleidoscope.clj`, replace the entire `KALEIDOSCOPE-ACCESS-CONTROL-LIST` def (lines 35-124) with the three-segment version below. The comment block explaining fail-closed (currently lines 84-94, 120-123) is preserved on the catch-all.

```clojure
(def api-resource-access-rules
  "Authorization for the JSON API resource routes — the rules that get an
  /api/v1 twin. The twins are DERIVED from this one list (see
  KALEIDOSCOPE-ACCESS-CONTROL-LIST), so the two address spaces cannot drift.
  Order matters where patterns overlap under buddy's whole-string matching:
  `^/projects-portfolio` (public) must precede `^/projects.*` (writer)."
  [{:pattern #"^/articles.*"     :handler auth/require-*-writer}
   {:pattern #"^/branches.*"     :handler auth/require-*-writer}
   {:pattern #"^/compositions.*" :handler auth/public-access}
   {:pattern #"^/groups.*"            :handler auth/require-*-writer}
   {:pattern #"^/interests.*"         :handler auth/require-*-writer}
   {:pattern #"^/projects-portfolio"  :handler auth/public-access}
   {:pattern #"^/projects.*"          :handler auth/require-*-writer}
   {:pattern #"^/score-definitions.*" :handler auth/require-*-writer}
   {:pattern #"^/agents.*"            :handler auth/require-*-writer}
   {:pattern #"^/workflows.*"         :handler auth/require-*-writer}
   {:pattern #"^/workspace-roots.*"   :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :put    :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :post   :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :delete :handler auth/require-*-writer}
   {:pattern #"^/themes.*"    :request-method :get    :handler auth/public-access}
   {:pattern #"^/albums.*"            :handler auth/require-*-admin}
   {:pattern #"^/article-audiences.*" :handler auth/require-*-admin}
   ;; Recipes: GETs public so shared/public recipes render for anonymous
   ;; readers (list is access-filtered internally); writes require a writer.
   {:pattern #"^/recipes.*"             :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipes.*"             :request-method :post   :handler auth/require-*-writer}
   {:pattern #"^/recipes.*"             :request-method :put    :handler auth/require-*-writer}
   {:pattern #"^/recipes.*"             :request-method :delete :handler auth/require-*-writer}
   {:pattern #"^/recipe-labels.*"       :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipe-labels.*"       :handler auth/require-*-writer}
   {:pattern #"^/recipe-label-groups.*" :request-method :get    :handler auth/public-access}
   {:pattern #"^/recipe-label-groups.*" :handler auth/require-*-writer}
   {:pattern #"^/recipe-audiences.*"    :handler auth/require-*-writer}])

(defn with-api-v1-prefix
  "Rewrite a resource rule's :pattern from ^/foo… to ^/api/v1/foo…, preserving
  :request-method and :handler. Used to derive each root resource rule's
  /api/v1 twin so authorization can't diverge between the two address spaces."
  [rule]
  (update rule :pattern
          (fn [p] (re-pattern (str/replace-first (str p) #"^\^/" "^/api/v1/")))))

(def KALEIDOSCOPE-ACCESS-CONTROL-LIST
  (vec
   (concat
    ;; Root-only: infra, admin, media, and the already-namespaced photos.
    ;; None of these get an /api/v1 twin (admin/photos/media are not part of
    ;; the dual-mounted resource surface; infra is single-address).
    [{:pattern #"^/admin.*"        :handler auth/require-*-admin}
     {:pattern #"^/$"              :handler auth/public-access}
     {:pattern #"^/index.html$"    :handler auth/public-access}
     {:pattern #"^/ping"           :handler auth/public-access}

     {:pattern #"^/media.*" :request-method :post :handler auth/require-*-writer}
     {:pattern #"^/media.*" :request-method :get  :handler auth/public-access}

     {:pattern #"^/v2/photos.*"  :request-method :post :handler auth/require-*-writer}
     {:pattern #"^/v2/photos.*"  :request-method :put  :handler auth/require-*-admin}
     {:pattern #"^/v2/photos"    :request-method :get  :handler auth/require-*-writer}
     {:pattern #"^/v2/photos/.*" :request-method :get  :handler auth/public-access}]

    ;; API resource rules at their legacy root paths …
    api-resource-access-rules
    ;; … and their derived /api/v1 twins. Kept in the same order so overlapping
    ;; rules (projects-portfolio before projects.*) retain their precedence.
    (map with-api-v1-prefix api-resource-access-rules)

    ;; Everything below is intentionally public — listed explicitly so the
    ;; fail-closed catch-all can safely reject anything NOT named here.
    ;; A single missed/mistyped pattern is a full, invisible data exposure
    ;; with no error and no test failure to catch it (see 2026-07-03 PLAN.md,
    ;; "Critical finding #4").
    [{:pattern #"^/openapi\.json$" :handler auth/public-access}
     {:pattern #"^/api-docs.*"     :handler auth/public-access}
     {:pattern #"^/favicon\.ico$"  :handler auth/public-access}
     {:pattern #"^/assets.*"       :handler auth/public-access}
     {:pattern #"^/static.*"       :handler auth/public-access}
     ;; `/` and `/favicon.ico` carry route-level :uri that wrap-force-uri
     ;; rewrites before wrap-access-rules runs, so both forms need a pattern.
     {:pattern #"^index\.html$"         :handler auth/public-access}
     {:pattern #"^static/favicon\.ico$" :handler auth/public-access}
     {:pattern #"^/registration.*" :handler auth/public-access}
     {:pattern #"^/check-domain.*" :handler auth/public-access}
     {:pattern #"^/v1/payments.*"  :handler auth/public-access}

     ;; Fail closed: anything not explicitly named above is rejected. MUST
     ;; stay last.
     {:pattern #"^/.*" :handler (constantly false)}])))
```

- [ ] **Step 4: Run the new tests + the existing ACL tests to verify they pass**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-twin-coverage-test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-acl-authorizes-like-root-test --focus kaleidoscope.http-api.kaleidoscope-test/access-control-list-fails-closed-test --focus kaleidoscope.http-api.kaleidoscope-test/access-rule-configuration-test`
Expected: PASS (4 tests). The last two are the pre-existing ACL tests — they must still pass, proving root behavior is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "feat(routing): derive /api/v1 ACL twins from resource rules

- Extract api-resource-access-rules + with-api-v1-prefix
- Rebuild KALEIDOSCOPE-ACCESS-CONTROL-LIST from three segments so each
  /api/v1 twin is computed, not hand-maintained (can't drift, fail-closed)
- Twin-coverage + auth-parity tests

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: Dual-mount the API route groups under `/api/v1`

Mount the root-rooted API resource route groups a second time under an `/api/v1` context, so every resource is reachable at both `/x` and `/api/v1/x` with identical handlers and (via Task 1) identical authorization.

**Files:**
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj:206-253` (the `kaleidoscope-app` router route vector)
- Test: `test/kaleidoscope/http_api/kaleidoscope_test.clj`

**Interfaces:**
- Consumes: the `/api/v1` ACL twins from Task 1.
- Produces: `api-route-groups` (vec of the reitit route groups that are dual-mounted). Excludes `reitit-photos-routes` (already namespaced at `/v2/photos`; folding it is deferred to Task 5) and the infra/index/admin routes.

- [ ] **Step 1: Write the failing test**

Add to `test/kaleidoscope/http_api/kaleidoscope_test.clj`:

```clojure
(deftest api-v1-dual-mount-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (testing "GET /api/v1/recipes reaches the same public handler as GET /recipes"
      (is (match? {:status 200 :body sequential?}
                  (app (mock/request :get "https://andrewslai.com/recipes"))))
      (is (match? {:status 200 :body sequential?}
                  (app (mock/request :get "https://andrewslai.com/api/v1/recipes")))))
    (testing "GET /api/v1/projects-portfolio is public, like the root path"
      (is (match? {:status 200}
                  (app (mock/request :get "https://andrewslai.com/api/v1/projects-portfolio")))))
    (testing "POST /api/v1/recipes still requires a writer (401 unauthenticated)"
      (is (match? {:status 401}
                  (app (-> (mock/request :post "https://andrewslai.com/api/v1/recipes")
                           (mock/json-body {}))))))))
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-dual-mount-test`
Expected: FAIL — `/api/v1/recipes` is unmounted, so the default handler answers (currently 200 HTML shell or, post-Task-3, 404), not a JSON list.

- [ ] **Step 3: Extract `api-route-groups` and dual-mount**

In `src/kaleidoscope/http_api/kaleidoscope.clj`, add this def just above `kaleidoscope-app` (after `inject-components`, ~line 204):

```clojure
(def api-route-groups
  "Root-rooted JSON API resource route groups, dual-mounted at their legacy root
  paths and under /api/v1 during the transition. Excludes reitit-photos-routes
  (already namespaced at /v2/photos — folding it under /api/v1 is deferred) and
  the ping/openapi/index/admin infra routes."
  [reitit-albums-routes
   reitit-articles-routes
   reitit-audiences-routes
   reitit-branches-routes
   reitit-compositions-routes
   reitit-groups-routes
   reitit-interests-routes
   reitit-projects-routes
   reitit-score-definition-routes
   reitit-agent-routes
   reitit-task-routes
   reitit-workflow-routes
   reitit-project-workflow-routes
   reitit-workspace-roots-routes
   reitit-portfolio-routes
   reitit-recipes-routes
   reitit-recipe-labels-routes
   reitit-recipe-label-groups-routes
   reitit-recipe-audiences-routes
   reitit-themes-routes])
```

Then replace the router's route vector (currently the literal vector at lines 221-252, from `reitit-ping-routes` through `reitit-themes-routes`) with:

```clojure
       (into [;; Administrative/helpers + already-namespaced photos (root only)
              reitit-ping-routes
              reitit-openapi-routes
              reitit-index-routes
              reitit-admin-routes
              reitit-photos-routes]
             (concat
              ;; API resource groups at their legacy root paths …
              api-route-groups
              ;; … and the same groups under /api/v1 (:no-doc keeps the
              ;; transition duplicates out of the OpenAPI spec).
              [(into ["/api/v1" {:no-doc true}] api-route-groups)]))
```

- [ ] **Step 4: Run the test + a broad regression check**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/api-v1-dual-mount-test`
Expected: PASS.

Run the full HTTP suite to confirm no root route regressed and reitit reports no route conflicts:
Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test --focus kaleidoscope.http-api.recipes-test`
Expected: PASS (no conflicting-routes exception at router build; all existing assertions green).

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "feat(routing): dual-mount API resource routes under /api/v1

- Extract api-route-groups; mount them at root (legacy) and under an
  /api/v1 {:no-doc true} context
- Photos (/v2/photos) stays root-only; folding deferred
- Parity test: /api/v1/recipes matches /recipes handler + auth

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: Discriminating SPA fallback

Replace the default handler that serves `index.html` for *every* unmatched path with one that serves the shell only for GET/HEAD page navigations and returns a real 404 for unmatched paths under a reserved backend prefix (fixing soft-404s) — this is what lets deep, parameterized page URLs render the app.

**Files:**
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj` — add `not-found` to the `ring.util.http-response` require (line 31); add reserved-prefix helpers; rewrite the `create-default-handler` `:not-found` (lines 254-269)
- Test: `test/kaleidoscope/http_api/kaleidoscope_test.clj`

**Interfaces:**
- Produces: `reserved-backend-prefixes` (vec of strings), `reserved-backend-path?` (fn: uri → boolean), `spa-shell-request?` (fn: request → boolean). Consumed only by the default handler.

- [ ] **Step 1: Write the failing tests**

Add to `test/kaleidoscope/http_api/kaleidoscope_test.clj`. (The `in-memory` static adapter is pre-seeded with an `index.html` whose body is `"<div>Hello</div>"` — the same shell `home-test` asserts — so no manual seeding is needed.)

```clojure
(deftest spa-fallback-serves-shell-for-page-paths-test
  (let [app (->> {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                  "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "use-access-control-list"
                  "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS)
                 env/prepare-kaleidoscope
                 kaleidoscope/kaleidoscope-app
                 tu/wrap-clojure-response)]
    (testing "a deep, parameterized page path deep-links to the SPA shell"
      (is (match? {:status  200
                   :headers {"Content-Type" #"text/html"}
                   :body    "<div>Hello</div>"}
                  (app (mock/request :get (str "https://andrewslai.com/library/"
                                               (random-uuid) "/acquisitions"))))))
    (testing "a bare unknown page path also serves the shell"
      (is (match? {:status 200 :body "<div>Hello</div>"}
                  (app (mock/request :get "https://andrewslai.com/about")))))
    (testing "an unmatched /api/v1 path 404s instead of serving the shell"
      (is (match? {:status 404}
                  (app (mock/request :get "https://andrewslai.com/api/v1/does-not-exist")))))
    (testing "a non-GET unmatched path 404s instead of serving HTML"
      (is (match? {:status 404}
                  (app (mock/request :post "https://andrewslai.com/library/whatever")))))))
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/spa-fallback-serves-shell-for-page-paths-test`
Expected: FAIL — the current default handler serves `"<div>Hello</div>"` (200) for the `/api/v1/does-not-exist` and `POST` cases too, so those two assertions fail with 200 ≠ 404.

- [ ] **Step 3: Add the require, the helpers, and rewrite the default handler**

In the `ns` require, change line 31 from:

```clojure
   [ring.util.http-response :refer [found]]
```
to:
```clojure
   [ring.util.http-response :refer [found not-found]]
```

Add these defs just above `kaleidoscope-app` (near `get-static-resource`, ~line 130):

```clojure
(def reserved-backend-prefixes
  "Path prefixes the backend owns. A request whose URI is one of these (or nested
  under it) is never a frontend page: an *unmatched* path under one must 404, not
  serve the SPA shell, so a bad asset/API URL fails honestly instead of returning
  HTML to a fetch caller (a soft 404)."
  ["/api/v1" "/api-docs" "/assets" "/static" "/media" "/v2/photos"
   "/openapi.json" "/favicon.ico" "/ping"])

(defn reserved-backend-path?
  [uri]
  (boolean (some (fn [p] (or (= uri p) (str/starts-with? uri (str p "/"))))
                 reserved-backend-prefixes)))

(defn spa-shell-request?
  "True when an unmatched request should fall through to the SPA shell for
  client-side routing: a GET/HEAD navigation for a path the backend does not
  reserve. Anything else — a non-GET, or an unmatched path under a reserved
  backend prefix — is a genuine miss and must 404."
  [{:keys [request-method uri]}]
  (and (contains? #{:get :head} request-method)
       (not (reserved-backend-path? uri))))
```

Replace the `create-default-handler` form (lines 254-269) with:

```clojure
      (ring/create-default-handler
       {:not-found (fn [request]
                     (if (spa-shell-request? request)
                       ;; Client-side routing: serve the SPA shell. This handler
                       ;; bypasses the reitit middleware stack, so set the
                       ;; shared-shell store (:asset-store) and :uri manually —
                       ;; the same values wrap-resolve-tenant/wrap-force-uri
                       ;; would apply — and inject :components directly.
                       (span/with-span! {:name "kaleidoscope.default.handler.get"}
                         (get-static-resource (-> request
                                                  (assoc :tenant {:asset-store "kaleidoscope.client"})
                                                  (assoc :uri "index.html")
                                                  (assoc :components components))))
                       (not-found {:reason "Not found"})))})
```

- [ ] **Step 4: Run the new tests + the shell/home regressions**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/spa-fallback-serves-shell-for-page-paths-test --focus kaleidoscope.http-api.kaleidoscope-test/home-test --focus kaleidoscope.http-api.kaleidoscope-test/static-chrome-serves-from-the-shared-client-store-test`
Expected: PASS (3 tests) — the new discrimination works and the existing shell/static-chrome behavior is unchanged.

- [ ] **Step 5: Commit**

```bash
git add src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "feat(routing): discriminating SPA fallback (no soft-404s)

- Serve index.html only for GET/HEAD, non-reserved paths so deep,
  parameterized page URLs (/library/:id/acquisitions) deep-link
- Return a real 404 for unmatched paths under a reserved backend prefix
  (/api/v1, /assets, …) and for non-GET unmatched requests

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: Document the URL structure

Record the reserved-prefix contract and the `/api/v1` transition in `docs/operations.md` (CLAUDE.md sharp edge #6 — the API surface is an operational contract), and flag the deferred Checkly path move.

**Files:**
- Modify: `docs/operations.md`
- Test: none (docs only — verified by the full suite still passing).

- [ ] **Step 1: Add the URL-structure subsection**

In `docs/operations.md`, add this section immediately before the `## Synthetic monitoring (Checkly)` heading (currently around line 73):

```markdown
## URL structure (API vs pages)

The root URL namespace belongs to the **frontend** (`kaleidoscope-ui`). The
backend owns a fixed set of **reserved prefixes**; every other path is a page
and is served the SPA shell (`index.html`) for client-side routing:

| Reserved | Purpose |
|---|---|
| `/api/v1/*` | JSON API — all resource routes |
| `/assets/*`, `/static/*`, `/media/*` | SPA assets + tenant static/media |
| `/v2/photos/*` | Photos (already namespaced; not yet folded under `/api/v1`) |
| `/favicon.ico`, `/index.html`, `/openapi.json`, `/api-docs/*`, `/ping` | Infra |

**Transition state:** the API is **dual-mounted** — every resource is reachable
at both its legacy root path (`/recipes`) and under `/api/v1` (`/api/v1/recipes`),
with identical authorization (the `/api/v1` ACL rules are derived from the root
rules in `http_api/kaleidoscope.clj`). An unmatched path under a reserved prefix
returns a real `404`; any other GET/HEAD path returns the SPA shell.

**Retirement (future, gated on the frontend):** once `kaleidoscope-ui` calls
`/api/v1/*` and the Checkly suites are repointed, the root API mounts are
removed so the root is pages-only and `/recipes` (etc.) deep-link to the app.
Until then, the Checkly checks below intentionally still target the root paths.
```

- [ ] **Step 2: Verify the suite is still green (no code changed, sanity only)**

Run: `task test:summary`
Expected: no new failures versus the Task 3 baseline.

- [ ] **Step 3: Commit**

```bash
git add docs/operations.md
git commit -m "docs(ops): document the /api/v1 reserved-prefix URL contract

- Reserved prefixes vs page namespace; dual-mount transition state
- Note the deferred root-mount retirement + Checkly repoint

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

## Deferred / gated tasks — DO NOT execute in this pass

These land only after the coordinated `kaleidoscope-ui` change ships. They are captured here so the sequencing isn't lost.

### Task 5 (deferred, optional): Fold `/v2/photos` into `/api/v1/photos`

Not required for the routing goal (`/v2/photos` is already namespaced and doesn't collide with a page). Deferred because `photo.clj` embeds `/v2/photos/...` self-links in its response bodies (`http_api/photo.clj:99,120,140`), so a clean fold means re-homing the route group to a `/photos`-rooted vector, mounting it at both `/v2/photos` (legacy) and `/api/v1/photos` (canonical), and updating those embedded paths + `photos-test`. Undertake only when unifying the API surface is worth that churn.

### Task 6 (deferred, gated on `kaleidoscope-ui` cutover): Retire the root API mounts

**Precondition:** `kaleidoscope-ui` ships a History-API client router, points all API calls at `/api/v1/*`, and the Checkly suites are repointed to `/api/v1`. **This is the step that actually fixes the `/recipes`-style collision** — do not run it before the precondition holds, or the deployed frontend breaks.

Steps, when unblocked:
1. In `kaleidoscope-app`, mount **only** `(into ["/api/v1"] api-route-groups)` (drop the bare `api-route-groups` root mount); remove the `{:no-doc true}` so `/api/v1` becomes the documented surface.
2. In the ACL, delete `api-resource-access-rules` from the root segment, keeping only the derived `/api/v1` twins (the twin-coverage test flips to asserting the root rules are *absent*).
3. Update/repoint the Checkly checks in `docs/operations.md`'s monitoring section and the check definitions.
4. Add a test: `GET https://andrewslai.com/recipes` (browser `Accept`) → `200` SPA shell; `GET /api/v1/recipes` → `200` JSON list.

### Frontend (separate repo `kaleidoscope-ui`, out of scope for this plan)

The `/library/:id/acquisitions` acceptance path cannot pass on backend changes alone. `kaleidoscope-ui` must: add a History-API client router that renders the view for `location.pathname` (including nested param routes); repoint all API calls to `/api/v1/*`; add an in-app 404 view.

---

## Self-review

**Spec coverage:**
- Reserved-prefix contract (DESIGN §1) → Task 3 `reserved-backend-prefixes`; documented in Task 4.
- Backend stays page-ignorant (DESIGN §2) → Task 3 (fallback keys off prefixes, not a page list); the `/library/:id/acquisitions` acceptance test is in Task 3.
- Dual-mount API under `/api/v1` (DESIGN §3a) → Task 2.
- Mirror the ACL (DESIGN §3b) → Task 1, with drift-proof derivation + coverage test.
- Discriminating fallback / soft-404 fix (DESIGN §3c) → Task 3.
- Collision fix lands at retirement (DESIGN §4) → Deferred Task 6, explicitly gated.
- `/v2/photos` fold (DESIGN §1 note) → Deferred Task 5, with the embedded-path rationale.
- Frontend router (DESIGN §5) → captured as out-of-scope coordinated work.
- Tenant-seam compatibility (DESIGN §7) → fallback reuses `:tenant {:asset-store "kaleidoscope.client"}`, matching the current seam; no change needed.
- Docs/ops (DESIGN "Docs / ops") → Task 4; Checkly repoint deferred to Task 6 (correct: root still works during transition).
- Testing strategy (DESIGN) → dual-mount parity (T2), twin coverage (T1), fallback discrimination incl. the deep-param acceptance path and 404s (T3), post-retirement (T6).

**Placeholder scan:** none — every step has concrete code/commands.

**Type consistency:** `api-resource-access-rules`, `with-api-v1-prefix`, `api-route-groups`, `reserved-backend-prefixes`, `reserved-backend-path?`, `spa-shell-request?` are each defined once (Tasks 1–3) and referenced consistently. `not-found` is added to the require in Task 3 before first use.
