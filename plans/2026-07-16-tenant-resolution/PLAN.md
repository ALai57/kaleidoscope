# Tenant Resolution (Phases 1–2) — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: superpowers:subagent-driven-development (or superpowers:executing-plans). Steps use `- [ ]` checkboxes.

**Goal:** Serve ephemeral Fly environments as one pinned tenant, with content and assets fully isolated from production, and zero behavior change in prod/local.

**Architecture:** A `kaleidoscope.http-api.tenant` resolver runs once at the edge and puts **two sibling values** on the request — `:tenant` (identity, for DB scoping) and `:asset-store` (a store *name*, for files). They are independent: prod resolves both from the Host header; ephemeral resolves `:tenant` to `KALEIDOSCOPE_TENANT` (DB) and `:asset-store` to an isolated store. `get-resource` serves files from `(or ::forced-store :asset-store)` — a shared-shell route names its store (`kaleidoscope.client`), otherwise the resolved store. The isolated ephemeral store is a **normal named entry** in the adapters map (no map override). Tenant→bucket lives in one data file, `resources/tenants.json`, read by the app and the deploy scripts.

**Stack:** Clojure, reitit/ring, next.jdbc, Kaocha + matcher-combinators + ring-mock, Fly.io + bash + `jq`, S3.

## Global constraints

- **Staging never reads/writes prod data.** Ephemeral assets live in an isolated `s3://kal-ephemeral/tenant-assets/<slug>/` prefix (reads+writes); `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none` so uploads can't trigger prod resize infra. DB is already isolated (Neon branch).
- **One tenant registry:** `resources/tenants.json`. Never hardcode the tenant list in code or scripts.
- **Two concerns, two values:** `:tenant` scopes the DB; `:asset-store` selects the file store. Never overload one for the other.
- 3-layer separation (`http_api`→`api`→`persistence`); only `http_api` reads the request. Every change has tests. No schema migration (the `hostname` columns stay). Prod/local behavior unchanged (default resolver `host`). Keep `Taskfile.yml`↔`scripts/ephemeral/*` and `docs/operations.md` in sync (CLAUDE.md #5/#6). Test with `task test:summary` / `./bin/test --focus <ns>`.
- Spec: `plans/2026-07-16-tenant-resolution/DESIGN.md`.

---

# Phase 1 — Resolution seam + isolated assets

## Task 1: Tenant resolver namespace

**Files:** create `src/kaleidoscope/http_api/tenant.clj`, `test/kaleidoscope/http_api/tenant_test.clj`.
**Produces:** `host-resolver`, `fixed-resolver`, `wrap-resolve-tenant`, `ephemeral-asset-store`.

- [ ] **Test (fails):**

```clojure
(ns kaleidoscope.http-api.tenant-test
  (:require [kaleidoscope.http-api.tenant :as sut] [clojure.test :refer [deftest is]]))
(deftest host-resolver-test
  (is (= {:tenant "andrewslai.com" :asset-store "andrewslai.com"}
         (sut/host-resolver {:headers {"host" "andrewslai.com"}}))))
(deftest fixed-resolver-test
  (is (= {:tenant "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
         ((sut/fixed-resolver "andrewslai.com" "ephemeral-tenant-assets")
          {:headers {"host" "kal-eph-xyz.fly.dev"}}))))
(deftest wrap-resolve-tenant-test
  (let [captured (atom nil)
        handler  ((sut/wrap-resolve-tenant (sut/fixed-resolver "a.com" "s"))
                  (fn [req] (reset! captured req) {:status 200}))]
    (handler {:headers {"host" "x"}})
    (is (= "a.com" (:tenant @captured)))
    (is (= "s"     (:asset-store @captured)))))
```

Run: `./bin/test --focus kaleidoscope.http-api.tenant-test` → FAIL (ns missing).

- [ ] **Implement:**

```clojure
(ns kaleidoscope.http-api.tenant
  "Resolve, once at the edge, the two independent facts a request needs:
  :tenant (identity → DB scoping) and :asset-store (a store name → files)."
  (:require [kaleidoscope.http-api.http-utils :as http-utils]))

(def ephemeral-asset-store
  "Name of the isolated per-env asset store an ephemeral deploy registers and the
  fixed resolver points at. Shared with the s3 launcher in init/env.clj."
  "ephemeral-tenant-assets")

(defn host-resolver
  "Prod/local: identity AND store are the Host header (the tenant's own bucket)."
  [request]
  (let [host (http-utils/get-host request)]
    {:tenant host :asset-store host}))

(defn fixed-resolver
  "Ephemeral: pin identity to `tenant-host` (DB) and the store to `asset-store`
  (the isolated store), independently."
  [tenant-host asset-store]
  (fn [_request] {:tenant tenant-host :asset-store asset-store}))

(defn wrap-resolve-tenant
  "Merge the resolver's {:tenant .. :asset-store ..} onto the request."
  [resolve-fn]
  (fn [handler] (fn [request] (handler (merge request (resolve-fn request))))))
```

Run test → PASS. Commit: `feat(tenant): edge resolver for :tenant + :asset-store`.

---

## Task 2: Static-content lookup consumes the resolved store

**Files:** modify `src/kaleidoscope/http_api/http_utils.clj` (add `site-value`, `forced-store-key`, `asset-store`; change `get-resource` at `:35-55`); test `http_utils_test.clj`.
**Interfaces:** `site-value [req]` (DB identity, host fallback); `forced-store-key` = `::forced-store`; `asset-store [req]` = `(or forced :asset-store)` — **no host fallback**.

- [ ] **Test (fails):**

```clojure
(deftest site-value-test
  (is (= "andrewslai.com" (sut/site-value {:tenant "andrewslai.com" :headers {"host" "x"}})))
  (is (= "x"              (sut/site-value {:headers {"host" "x"}}))))          ; fallback for pre-resolver paths
(deftest asset-store-test
  (is (= "kaleidoscope.client" (sut/asset-store {sut/forced-store-key "kaleidoscope.client" :asset-store "a"})))
  (is (= "a"                   (sut/asset-store {:asset-store "a"})))
  (is (nil? (sut/asset-store {}))))                                             ; no store, no fallback
```

Run: `./bin/test --focus kaleidoscope.http-api.http-utils-test` → FAIL.

- [ ] **Implement** (add after `bucket-name`, ~line 33):

```clojure
(defn site-value
  "The resolved tenant identity — scopes DB queries. Set by wrap-resolve-tenant;
  falls back to the Host header for paths that run before it (default handler)."
  [request] (or (:tenant request) (get-host request)))

(def forced-store-key
  "Request key set by wrap-force-store when a route names a shared store." ::forced-store)

(defn asset-store
  "The store name that serves this request's files: a route-forced shared store
  wins, else the store resolved at the edge. No Host fallback — callers that skip
  the middleware (default handler) set forced-store-key explicitly."
  [request] (or (get request forced-store-key) (:asset-store request)))
```

In `get-resource`, use the store for both the log and the lookup:

```clojure
  (log/infof "Getting resource at %s for %s" uri (asset-store request))
  (let [bucket  (asset-store request)
        adapter (get static-content-adapters bucket)
        ...]) ; rest unchanged
```

Run test → PASS. Commit: `feat(tenant): serve static content from the resolved :asset-store`.

---

## Task 3: Route-forced shared stores + explicit default handler

**Files:** modify `middleware.clj` (`wrap-force-host`→`wrap-force-store`, `:235-249`, and its entry in `base-middleware`); `kaleidoscope.clj` (routes `:host`→`:store` at `:140,146`; default handler `:261-266`). Test `middleware_test.clj`.

- [ ] **Test (fails):**

```clojure
(defn- run-force [store inner]
  (((:compile sut/wrap-force-store) {:store store} {}) inner))
(deftest force-store-test
  (let [c (atom nil)] ((run-force "kaleidoscope.client" (fn [r] (reset! c r) {})) {})
       (is (= "kaleidoscope.client" (get @c http-utils/forced-store-key))))
  (let [c (atom nil)] ((run-force nil (fn [r] (reset! c r) {})) {})
       (is (nil? (get @c http-utils/forced-store-key)))))
```

- [ ] **Implement** — replace `wrap-force-host` with `wrap-force-store` (sets the store key only; no Host mutation):

```clojure
(def wrap-force-store
  "If a route sets :store, serve its files from that named shared store
  (e.g. kaleidoscope.client for the SPA shell), bypassing tenant resolution."
  {:name    ::wrap-force-store
   :compile (fn [{:keys [store]} _]
              (fn [handler]
                (fn [request]
                  (span/with-span! {:name "kaleidoscope.mw.force-store"}
                    (handler (cond-> request store (assoc http-utils/forced-store-key store)))))))})
```

Update `base-middleware` to reference `wrap-force-store`. In `kaleidoscope.clj` `reitit-index-routes`, change `:host "kaleidoscope.client"` → `:store "kaleidoscope.client"` on `/` and `/assets/*` (`wrap-force-uri` and the favicon/`static`/`media` routes are unchanged — they serve from the tenant store). In the default not-found handler, replace `(mw/set-host "kaleidoscope.client")` with `(assoc http-utils/forced-store-key "kaleidoscope.client")`.

Run test + `task test:summary` → PASS (existing static tests still green: prod resolves `:asset-store` = host = the tenant/client bucket as before). Commit: `feat(tenant): route-named shared stores via forced-store; drop host mutation`.

---

## Task 4: Boot the resolver and wire it in

**Files:** modify `init/env.clj` (require `tenant`; boot instruction; `DEFAULT-BOOT-INSTRUCTIONS`; `prepare-kaleidoscope`); `kaleidoscope.clj` (`kaleidoscope-app` middleware). Test `kaleidoscope_test.clj`.

- [ ] **Test (fails):**

```clojure
(deftest tenant-resolver-boots-test
  (let [c (->> {"KALEIDOSCOPE_DB_TYPE" "embedded-h2" "KALEIDOSCOPE_AUTH_TYPE" "always-unauthenticated"
                "KALEIDOSCOPE_AUTHORIZATION_TYPE" "use-access-control-list" "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"
                "KALEIDOSCOPE_TENANT_RESOLVER_TYPE" "fixed" "KALEIDOSCOPE_TENANT" "andrewslai.com"
                "KALEIDOSCOPE_TENANT_ASSET_BUCKET" "kal-ephemeral"}
               (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS) env/prepare-kaleidoscope)]
    (is (match? {:tenant "andrewslai.com" :asset-store "ephemeral-tenant-assets"}
                ((:tenant-resolver c) {:headers {"host" "kal-eph-xyz.fly.dev"}})))))
(deftest tenant-resolver-default-host-test
  (let [c (->> {"KALEIDOSCOPE_DB_TYPE" "embedded-h2" "KALEIDOSCOPE_AUTH_TYPE" "always-unauthenticated"
                "KALEIDOSCOPE_AUTHORIZATION_TYPE" "use-access-control-list" "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"}
               (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS) env/prepare-kaleidoscope)]
    (is (match? {:tenant "andrewslai.com" :asset-store "andrewslai.com"}
                ((:tenant-resolver c) {:headers {"host" "andrewslai.com"}})))))
```

- [ ] **Implement** — require `[kaleidoscope.http-api.tenant :as tenant]` in `env.clj`. Boot instruction (after the static-content one):

```clojure
(def kaleidoscope-tenant-resolver-boot-instructions
  {:name :kaleidoscope-tenant-resolver :path "KALEIDOSCOPE_TENANT_RESOLVER_TYPE"
   :launchers {"host"  (fn [_] tenant/host-resolver)
               "fixed" (fn [env] (tenant/fixed-resolver
                                  (get env "KALEIDOSCOPE_TENANT")
                                  (if (get env "KALEIDOSCOPE_TENANT_ASSET_BUCKET")
                                    tenant/ephemeral-asset-store
                                    (get env "KALEIDOSCOPE_TENANT"))))}
   :default "host"})
```

Add it to `DEFAULT-BOOT-INSTRUCTIONS`; in `prepare-kaleidoscope` destructure `kaleidoscope-tenant-resolver` and return `:tenant-resolver kaleidoscope-tenant-resolver`. In `kaleidoscope.clj` require `tenant` and make `wrap-resolve-tenant` the first spliced middleware:

```clojure
(concat mw/base-middleware
        [(tenant/wrap-resolve-tenant (or (:tenant-resolver components) tenant/host-resolver))
         (mw/wrap-exception-reporter (:exception-reporter components))
         (inject-components components) (:session-tracking components) (:auth-stack components)]
        mw/coercion-middleware)
```

Run test + `task test:summary` → PASS. Commit: `feat(tenant): boot resolver + wire into app`.

---

## Task 5: Tenant registry → `resources/tenants.json`

De-hardcode the `s3` launcher's tenant map into one data file (also read by the deploy scripts). Shared stores (`pub`/`client`) and the `andrewslai.com.localhost` dev alias stay in code — they are plumbing/aliases, not deployable tenants.

**Files:** create `resources/tenants.json`; modify `init/env.clj` (requires `cheshire.core`/`clojure.java.io`; add `read-tenants`; build the `s3` tenant entries from it, `:219-232`); test `env_test.clj`.

- [ ] **Test (fails):**

```clojure
(ns kaleidoscope.init.env-test (:require [kaleidoscope.init.env :as env] [clojure.test :refer [deftest is]]))
(defn- s3 [m] ((get-in env/kaleidoscope-static-content-adapter-boot-instructions [:launchers "s3"]) m))
(deftest registry-test
  (is (contains? (set (map :hostname (env/read-tenants))) "andrewslai.com"))
  (is (= "wedding"          (:bucket (get (s3 {}) "caheriaguilar.and.andrewslai.com")))) ; bucket≠host proves file read
  (is (= "kaleidoscope.pub" (:bucket (get (s3 {}) "kaleidoscope.pub")))))
```

- [ ] **Implement** — `resources/tenants.json`:

```json
{"tenants":[
  {"hostname":"andrewslai.com","bucket":"andrewslai.com"},
  {"hostname":"caheriaguilar.com","bucket":"caheriaguilar.com"},
  {"hostname":"sahiltalkingcents.com","bucket":"sahiltalkingcents.com"},
  {"hostname":"caheriaguilar.and.andrewslai.com","bucket":"wedding"}]}
```

In `env.clj`:

```clojure
(defn read-tenants
  "Tenant registry — single source of truth (also read by scripts/ephemeral/lib.sh
  via jq). Edit resources/tenants.json to onboard a tenant."
  [] (-> (io/resource "tenants.json") slurp (json/parse-string true) :tenants))
```

In the `s3` launcher, `merge` the file-built tenant adapters onto the hardcoded shared stores + `.localhost` alias (keep `kaleidoscope.pub`, `kaleidoscope.client` with `CLIENT_BUCKET/PREFIX`, `andrewslai.com.localhost`→`andrewslai.com`), and drop the four literal tenant lines:

```clojure
(cond-> (merge {"kaleidoscope.pub"    (s3-storage/make-s3 {:bucket "kaleidoscope.pub"})
                "kaleidoscope.client" (s3-storage/make-s3 (cond-> {:bucket (or (get env "KALEIDOSCOPE_CLIENT_BUCKET") "kaleidoscope.client")}
                                                            (get env "KALEIDOSCOPE_CLIENT_PREFIX") (assoc :prefix (get env "KALEIDOSCOPE_CLIENT_PREFIX"))))
                "andrewslai.com.localhost" (s3-storage/make-s3 {:bucket "andrewslai.com"})}
               (into {} (map (fn [{:keys [hostname bucket]}] [hostname (s3-storage/make-s3 {:bucket bucket})])) (read-tenants)))
  ;; (Task 6 adds the isolated-store entry here.)
  )
```

Run test + `task test:summary` → PASS (adapter map identical, just file-sourced). *(cognitect aws clients are lazy; if `aws/client` fails offline in CI, assert on a pure `{:bucket ..}` helper instead.)* Commit: `refactor(tenant): extract registry to resources/tenants.json`.

---

## Task 6: Register the isolated ephemeral store

**Files:** modify `init/env.clj` (`s3` launcher override point from Task 5); test `env_test.clj`.

- [ ] **Test (fails):**

```clojure
(deftest isolated-store-test
  (let [a (get (s3 {"KALEIDOSCOPE_TENANT_ASSET_BUCKET" "kal-ephemeral"
                    "KALEIDOSCOPE_TENANT_ASSET_PREFIX" "tenant-assets/xyz/"}) "ephemeral-tenant-assets")]
    (is (= "kal-ephemeral" (:bucket a)))
    (is (= "tenant-assets/xyz/" (:prefix a))))
  (is (nil? (get (s3 {}) "ephemeral-tenant-assets")))                 ; inert in prod
  (is (= "andrewslai.com" (:bucket (get (s3 {"KALEIDOSCOPE_TENANT_ASSET_BUCKET" "kal-ephemeral"}) "andrewslai.com"))))) ; tenant entry untouched
```

- [ ] **Implement** — at the Task 5 override point, **add a new named entry** (not an override):

```clojure
  (get env "KALEIDOSCOPE_TENANT_ASSET_BUCKET")
  (assoc tenant/ephemeral-asset-store
         (s3-storage/make-s3 (cond-> {:bucket (get env "KALEIDOSCOPE_TENANT_ASSET_BUCKET")}
                               (get env "KALEIDOSCOPE_TENANT_ASSET_PREFIX")
                               (assoc :prefix (get env "KALEIDOSCOPE_TENANT_ASSET_PREFIX")))))
```

Run test → PASS. Commit: `feat(tenant): register isolated ephemeral asset store`.

---

## Task 7: Seed sample assets + registry-backed validation

**Files:** modify `scripts/ephemeral/lib.sh` (add `tenant_asset_prefix`, `known_tenant?` via jq over `resources/tenants.json`, `DEFAULT_TENANT`, `TENANTS_FILE`); create `scripts/ephemeral/seed-tenant-assets` + `test-resources/ephemeral-sample-assets/andrewslai.com/static/favicon.ico` (+ a `media/` sample); modify `scripts/ephemeral/up` (seed after `build-frontend`), `Taskfile.yml`. Test: repo lib.sh harness.

- [ ] **Test (fails):** `tenant_asset_prefix my-slug` = `tenant-assets/my-slug/`; `known_tenant? andrewslai.com` exits 0; `known_tenant? typo.example.com` exits ≠0. (Locate harness: `grep -rln parse_ephemeral_slugs test/ scripts/`; else add `scripts/ephemeral/lib_selftest.sh` run by a `task test:*` target.)

- [ ] **Implement** — in `lib.sh`:

```bash
tenant_asset_prefix() { printf 'tenant-assets/%s/' "$1"; }
TENANTS_FILE="${TENANTS_FILE:-$REPO_ROOT/resources/tenants.json}"
DEFAULT_TENANT="${DEFAULT_TENANT:-andrewslai.com}"
known_tenant?() { require_cmd jq; jq -e --arg h "$1" '.tenants[]|select(.hostname==$h)' "$TENANTS_FILE" >/dev/null 2>&1; }
```

`seed-tenant-assets` (source lib.sh): resolve SLUG + `TENANT="${TENANT:-$DEFAULT_TENANT}"`, `known_tenant? "$TENANT" || die`, `PREFIX=$(tenant_asset_prefix "$SLUG")`, `load_staging_env`, then `aws s3 sync "$REPO_ROOT/test-resources/ephemeral-sample-assets/$TENANT/" "s3://$EPHEMERAL_BUCKET/$PREFIX" --delete` (die if the fixtures dir is missing). `chmod +x`. Populate the fixtures with a real (no-PII) `static/favicon.ico` + a small `media/` sample. Insert `seed-tenant-assets --name="$SLUG"` into `up` between `build-frontend` and `deploy-app`; add the matching `ephemeral:seed-tenant-assets` Taskfile target and forward `TENANT`.

Run harness + `bash -n scripts/ephemeral/seed-tenant-assets` → PASS. Commit: `feat(ephemeral): seed isolated tenant assets; validate TENANT via registry`.

---

## Task 8: Wire ephemeral deploy

**Files:** modify `scripts/ephemeral/deploy-app`, `docs/operations.md`.

- [ ] After `SLUG`/`APP`: `TENANT="${TENANT:-$DEFAULT_TENANT}"; known_tenant? "$TENANT" || die; ASSET_PREFIX="$(tenant_asset_prefix "$SLUG")"`.
- [ ] In `fly secrets set`: **remove** the three `KALEIDOSCOPE_EPHEMERAL_HOST_*`; **add** `KALEIDOSCOPE_TENANT_RESOLVER_TYPE=fixed`, `KALEIDOSCOPE_TENANT="$TENANT"`, `KALEIDOSCOPE_TENANT_ASSET_BUCKET="$EPHEMERAL_BUCKET"`, `KALEIDOSCOPE_TENANT_ASSET_PREFIX="$ASSET_PREFIX"`, `KALEIDOSCOPE_IMAGE_NOTIFIER_TYPE=none`. Keep `KALEIDOSCOPE_CLIENT_BUCKET/_PREFIX`.
- [ ] Add a warn if `s3://$EPHEMERAL_BUCKET/${ASSET_PREFIX}static/favicon.ico` is absent (assets unseeded).
- [ ] Verify: `bash -n scripts/ephemeral/deploy-app; grep -n 'KALEIDOSCOPE_TENANT\|IMAGE_NOTIFIER\|EPHEMERAL_HOST' scripts/ephemeral/deploy-app` (present / **no** EPHEMERAL_HOST).
- [ ] `docs/operations.md`: `TENANT` input (validated via `resources/tenants.json`), fixed resolver, isolated `tenant-assets/<slug>/`, notifier `none`, `EPHEMERAL_HOST_*` removed, tenant registry file, DB-seeding prerequisite (branch needs the tenant's rows, esp. `themes`).

Commit: `feat(ephemeral): pin tenant + isolate assets; drop EPHEMERAL_HOST_*`.

---

# Phase 2 — DB scoping

Mechanically replace `(hu/get-host request)` → `(hu/site-value request)` (DB identity). Behavior-preserving in prod. `api/`/`persistence/` untouched.

## Task 9: Scope articles / recipes / themes / audiences

**Files:** `http_api/{articles,recipes,themes,audiences}.clj` (9/20/3/2 sites). Tests: `kaleidoscope_test.clj` (articles/themes/audiences), `recipes_test.clj`.

- [ ] **Guard test** (add to `kaleidoscope_test.clj`) — under the fixed resolver a fly.dev host still serves the pinned tenant's content:

```clojure
(deftest fixed-resolver-scopes-content-test
  (let [c   (->> {"KALEIDOSCOPE_DB_TYPE" "embedded-h2" "KALEIDOSCOPE_AUTH_TYPE" "always-unauthenticated"
                  "KALEIDOSCOPE_AUTHORIZATION_TYPE" "use-access-control-list" "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "none"
                  "KALEIDOSCOPE_TENANT_RESOLVER_TYPE" "fixed" "KALEIDOSCOPE_TENANT" "andrewslai.com"}
                 (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS) env/prepare-kaleidoscope)
        _   (articles-api/create-article! (:database c)
             (assoc (first (vals models.articles/example-articles)) :hostname "andrewslai.com"))
        app (->> c kaleidoscope/kaleidoscope-app tu/wrap-clojure-response)]
    ;; body matcher: copy the existing /compositions test's matcher; must assert NON-empty
    (is (match? {:status 200 :body (m/embeds [{:article-url string?}])}
                (app (mock/request :get "https://kal-eph-xyz.fly.dev/compositions"))))))
```

Run → FAILS (handlers still scope by the fly.dev host). Then per file: `sed -i '' 's/hu\/get-host/hu\/site-value/g' src/kaleidoscope/http_api/<f>.clj`, `grep -c hu/get-host <f>` = 0, run that file's suite → PASS. Baseline `recipes_test`/`kaleidoscope_test` before each. Commit per file (or one: `refactor(tenant): scope content handlers by :tenant identity`).

---

## Task 10: Scope photo (DB) + isolate uploads (store)

**Files:** `http_api/photo.clj` (`:48,96,134,159`). Test `photo_test.clj`.
Photo uses the host for **two** things: DB scoping (→ `hu/site-value`) and the **upload adapter** (→ `hu/asset-store`, so ephemeral uploads write to the isolated store).

- [ ] Baseline `photo-test` → PASS.
- [ ] Swap the DB-scoping sites (`:96,134,159`) `hu/get-host`→`hu/site-value`. At the upload-adapter selection (`:48`, `(get static-content-adapters hostname)`), use `(hu/asset-store req)` instead.
- [ ] `grep -c hu/get-host src/kaleidoscope/http_api/photo.clj` = 0; `photo-test` → PASS.

Commit: `refactor(tenant): photo DB by :tenant, uploads to :asset-store`.

---

## Task 11: Verify + docs

- [ ] `task test:summary` → no failures.
- [ ] `grep -rn 'hu/get-host' src/kaleidoscope/http_api/{articles,recipes,themes,audiences,photo}.clj` → none (legit uses remain in `http_utils`/`tenant`/`virtual_hosting`).
- [ ] `docs/operations.md`: "Ephemeral tenancy" — pinned tenant (`TENANT`), content scoped, assets+uploads confined to `tenant-assets/<slug>/`, notifier `none`.

Commit: `docs(ops): ephemeral tenant impersonation + isolation`.

---

## Coverage / decisions

- Resolve once, two sibling values (`:tenant` DB, `:asset-store` files) → T1, T2, T4. Forced shared stores + explicit default handler (no Host fallback) → T3. Registry file → T5. Isolated store as a **named entry**, no map override → T6. Deploy/seed/validation → T7, T8. DB scoping → T9, T10. Uploads safe by isolation (write to the isolated store) → T6, T10.
- **`:asset-store` carries a store *name*** (not the adapter): loggable, registry-backed, keeps the isolated store a normal named entry — deliberate.
- **`.localhost` needs no special guard:** in ephemeral `:asset-store` is always the isolated store regardless of pinned identity, so no pinned value can reach a prod bucket.
- Out of scope (non-goals): DB column migration, DB-backed registry (file only), RLS, multi-tenant-per-env, auth role strings, north-star.
