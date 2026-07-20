# Single Static Asset Store Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Serve all tenants' `/static/*` site chrome and `/favicon.ico` from a single shared asset store (`kaleidoscope.client`) using andrewslai.com's assets as the canonical set, and delete the now-redundant per-tenant static-asset seeding, folders, and deploy scripts — so tenancy lives only in the DB (hostname) and per-tenant look in `themes.config`, never in *which bucket static bytes sit in*.

**Architecture:** The router already serves `/` and `/assets/*` from a shared store via a route-level `:store "kaleidoscope.client"` override (consumed by `wrap-resolve-tenant`). This plan extends that override to `/static/*` and `/favicon.ico`, so those routes stop resolving the request's per-tenant `:asset-store`. The frontend ships andrewslai's `static/` into the `kaleidoscope.client` bucket alongside the SPA (prod) and into the `eph-<slug>/static/` prefix (ephemeral), replacing the backend's fixture-based `seed-tenant-assets`. **Explicitly scoped OUT (Option A):** `/media/*` (article-embedded images) keeps using the per-tenant `:asset-store` — its storage move + access-control model are the separate `article-embedded-asset-acl` plan. So the `:asset-store` concept and the per-tenant adapter map survive this plan; only static chrome moves off them.

**Tech Stack:** Clojure (reitit routing, `wrap-resolve-tenant` middleware), Kaocha + matcher-combinators + ring-mock tests with embedded-h2 / in-memory filesystem, bash + AWS CLI deploy scripts in two repos (`kaleidoscope` backend, `kaleidoscope-ui` frontend), Vite build.

## Global Constraints

- **Two repos, land together.** Backend tasks are in `kaleidoscope`; frontend tasks are in the sibling `kaleidoscope-ui` checkout (default `../kaleidoscope-ui`, override `FRONTEND_DIR`). The backend route change (Task 1) and the frontend shipping change (Task 2) must deploy together or ephemeral/prod `/static/*` briefly 404s — sequence Task 2 before Task 1 reaches prod.
- **Canonical assets = andrewslai.com.** `kaleidoscope-ui/resources/andrewslai.com/static/` is the single source of truth for static chrome for every tenant. Per-tenant visual differences come from `themes.config` (DB), not static files.
- **`/media/*` is untouched.** Do not change the `/media/*` route, the per-tenant env.clj adapters, `host-resolver`/`fixed-resolver`, or delete the per-tenant prod buckets — `/media/*` still reads article images from them (see the retirement guard in `docs/operations.md`).
- **Naming:** SQL `snake_case`, Clojure `kebab-case`. `!` on side-effecting fns. Serving stores are named by the static-content-adapter map key (`"kaleidoscope.client"`).
- **Every change ships with automated tests** (embedded-h2 / in-memory default). **Keep in sync in the same change:** `docs/operations.md` (any deploy/env change) and `Taskfile.yml` ↔ `bin/`/`scripts/`.
- **Frequent commits, TDD, DRY, YAGNI.**

---

## File Structure

| File | Repo | Responsibility | Change |
|---|---|---|---|
| `src/kaleidoscope/http_api/kaleidoscope.clj` | backend | Router: `/static/*` + `/favicon.ico` route data | Modify (add `:store`) |
| `test/kaleidoscope/http_api/kaleidoscope_test.clj` | backend | Prove static/favicon serve from the shared store, not the per-tenant one | Modify (add test) |
| `scripts/ephemeral/up` | backend | Ephemeral orchestration | Modify (drop seed step) |
| `scripts/ephemeral/seed-tenant-assets` | backend | Fixture seeder (obsolete) | Delete |
| `scripts/ephemeral/deploy-app` | backend | Secrets + guards | Modify (drop static favicon guard) |
| `test-resources/ephemeral-sample-assets/` | backend | Placeholder fixtures (obsolete) | Delete |
| `Taskfile.yml` | backend | `env:seed-tenant-assets` + `ephemeral:seed-tenant-assets` | Modify (remove tasks) |
| `docs/operations.md`, `CLAUDE.md` | backend | Ephemeral asset docs / architecture note | Modify |
| `scripts/deployment/deploy-kaleidoscope-client` | frontend | Prod SPA + static deploy | Modify (also sync static → client) |
| `scripts/deployment/deploy-ephemeral` | frontend | Ephemeral SPA + static deploy | Modify (also sync static → eph prefix) |
| `resources/andrewslai.com/static/favicon.ico` | frontend | The `/favicon.ico` object | Create (if absent) |
| `scripts/deployment/deploy-to-andrewslai`, `deploy-caheriaguilar-to-s3`, `deploy-sahiltalkingcents-to-s3` | frontend | Per-tenant static deploys (obsolete) | Delete |
| `resources/caheriaguilar.com/`, `resources/sahiltalkingcents.com/` | frontend | Redundant per-tenant static | Delete |

---

## Task 1 (backend): Route `/static/*` and `/favicon.ico` to the shared `kaleidoscope.client` store

**Files:**
- Modify: `src/kaleidoscope/http_api/kaleidoscope.clj` (the `reitit-index-routes` `/static/*` and `/favicon.ico` blocks, ~lines 136–156)
- Test: `test/kaleidoscope/http_api/kaleidoscope_test.clj`

**Interfaces:**
- Consumes: the existing `:store` route override read by `wrap-resolve-tenant` (`http_api/tenant.clj`) — a route's `:store` overrides the tenant's `:asset-store`, so `get-static-resource` → `hu/get-resource` resolves the named store. Already used by `/` and `/assets/*`.
- Produces: `/static/*` and `/favicon.ico` are served from the `"kaleidoscope.client"` adapter regardless of Host/tenant. `/media/*` is **unchanged** (still per-tenant `:asset-store`).

- [ ] **Step 1: Write the failing test.** Build the app with the in-memory launcher, then give ONLY the `kaleidoscope.client` store a marker static file (and empty the per-tenant `andrewslai.com` store), proving static now resolves via the shared store. Add to `kaleidoscope_test.clj`:

```clojure
(deftest static-chrome-serves-from-the-shared-client-store-test
  ;; /static/* and /favicon.ico serve from kaleidoscope.client for every tenant —
  ;; not the per-tenant asset-store. Give the client store a marker the tenant
  ;; store lacks; if the request 200s, it was served from the shared store.
  (let [system   (env/start-system! env/DEFAULT-BOOT-INSTRUCTIONS
                                     {"KALEIDOSCOPE_DB_TYPE"             "embedded-h2"
                                      "KALEIDOSCOPE_AUTH_TYPE"           "always-unauthenticated"
                                      "KALEIDOSCOPE_AUTHORIZATION_TYPE"  "public-access"
                                      "KALEIDOSCOPE_STATIC_CONTENT_TYPE" "in-memory"})
        adapters (:kaleidoscope-static-content-adapters system)
        mkfile   (fn [name s] (in-mem/file {:name name :version "1"
                                            :content (java.io.ByteArrayInputStream. (.getBytes (str s)))}))]
    ;; client store has the marker + favicon; the per-tenant store is empty
    (reset! (:store (get adapters "kaleidoscope.client"))
            {"static" {"marker.txt"  (mkfile "marker.txt" "CLIENT")
                       "favicon.ico" (mkfile "favicon.ico" "FAV")}})
    (reset! (:store (get adapters "andrewslai.com")) {})
    (let [app (->> system env/prepare-kaleidoscope kaleidoscope/kaleidoscope-app tu/wrap-clojure-response)]
      (testing "/static/* is served from kaleidoscope.client though the tenant store is empty"
        (is (= 200 (:status (app (mock/request :get "https://andrewslai.com/static/marker.txt"))))))
      (testing "/favicon.ico is served from kaleidoscope.client (static/favicon.ico)"
        (is (= 200 (:status (app (mock/request :get "https://andrewslai.com/favicon.ico")))))))))
```

Ensure the test ns requires `[kaleidoscope.persistence.filesystem.in-memory-impl :as in-mem]` (add if missing).

- [ ] **Step 2: Run to verify it fails.**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/static-chrome-serves-from-the-shared-client-store-test`
Expected: FAIL — both requests 404 (served from the empty per-tenant `andrewslai.com` store).

- [ ] **Step 3: Implement.** In `reitit-index-routes`, add `:store "kaleidoscope.client"` to the `:get` map of `/favicon.ico` and `/static/*` (mirroring `/` and `/assets/*`):

```clojure
   ["/favicon.ico" {:get {:span-name "kaleidoscope.favicon.get"
                          :uri       "static/favicon.ico"
                          :store     "kaleidoscope.client"
                          :handler   get-static-resource}}]
```

```clojure
   ["/static/*" {:conflicting true
                 :get         {:span-name (fn [{:keys [uri] :as _request}] (format "kaleidoscope.%s.get" (str/replace uri #"/" ".")))
                               :store     "kaleidoscope.client"
                               :handler   get-static-resource}}]
```

Leave `/media/*` exactly as-is (no `:store`).

- [ ] **Step 4: Run to verify it passes.**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test/static-chrome-serves-from-the-shared-client-store-test`
Expected: PASS.

- [ ] **Step 5: Run the full router + tenant suites to catch regressions.**

Run: `./bin/test --focus kaleidoscope.http-api.kaleidoscope-test --focus kaleidoscope.http-api.tenant-test`
Expected: PASS (the `/media/` and resolver tests still pass — `:asset-store` is unchanged).

- [ ] **Step 6: Commit.**

```bash
git add src/kaleidoscope/http_api/kaleidoscope.clj test/kaleidoscope/http_api/kaleidoscope_test.clj
git commit -m "feat(static): serve /static/* and /favicon.ico from the shared client store

- add :store kaleidoscope.client to the /static/* and /favicon.ico routes so
  static chrome resolves from one shared store, not the per-tenant asset-store
- /media/* (article images) is untouched — still per-tenant (deferred ACL plan)
- tenancy now lives only in the DB (hostname) + themes.config, not in static keys"
```

---

## Task 2 (frontend, kaleidoscope-ui): Ship andrewslai `static/` to the shared client store

**Files:**
- Modify: `scripts/deployment/deploy-kaleidoscope-client` (prod) and `scripts/deployment/deploy-ephemeral` (ephemeral)
- Create: `resources/andrewslai.com/static/favicon.ico` (only if absent — the `/favicon.ico` route fetches key `static/favicon.ico`)

**Interfaces:**
- Consumes: Task 1's routing (`/static/*`, `/favicon.ico` → `kaleidoscope.client` store).
- Produces: prod `s3://kaleidoscope.client/static/...` and ephemeral `s3://kal-ephemeral/eph-<slug>/static/...` both carry andrewslai's static tree, so Task 1's routes resolve in every env. `vite.config.ts` stays `copyPublicDir: false` (the andrewslai folder also holds legacy `media/`, which must NOT land in the client bucket — sync only `resources/andrewslai.com/static`).

- [ ] **Step 1: Ensure `static/favicon.ico` exists.** Check `resources/andrewslai.com/static/favicon.ico`. If absent (the repo currently has `static/images/nav-bar/favicon.{png,svg}`, not a root `favicon.ico`), create it — copy the existing favicon to that exact key so the `/favicon.ico` route resolves:

```bash
cd "$FRONTEND_DIR"   # ../kaleidoscope-ui
[ -f resources/andrewslai.com/static/favicon.ico ] || \
  cp resources/andrewslai.com/static/images/nav-bar/favicon.svg resources/andrewslai.com/static/favicon.ico
```

(If you prefer a real `.ico`, generate one; the route only needs bytes at that key. Committing the file is the deliverable.)

- [ ] **Step 2: Prod — sync andrewslai static into the client bucket.** In `deploy-kaleidoscope-client`, after the existing `aws s3 sync "$DIST_DIR/assets" s3://kaleidoscope.client/assets` and index.html copy, add:

```bash
echo "Deploying shared static chrome to kaleidoscope.client..."
aws s3 sync resources/andrewslai.com/static s3://kaleidoscope.client/static --delete --exclude "*/shareable_stories/*"
```

- [ ] **Step 3: Ephemeral — sync andrewslai static into the env prefix.** In `deploy-ephemeral`, after the existing `aws s3 sync "$DIST/" "s3://$EPHEMERAL_BUCKET/$PREFIX" --delete`, add (note: NOT `--delete` on this second sync — it would wipe the SPA already synced to the same prefix):

```bash
log "Syncing shared static chrome -> s3://$EPHEMERAL_BUCKET/${PREFIX}static ..."
aws s3 sync resources/andrewslai.com/static "s3://$EPHEMERAL_BUCKET/${PREFIX}static" --exclude "*/shareable_stories/*"
```

- [ ] **Step 4: Verify locally (dry-run the S3 sync).**

Run: `aws s3 sync resources/andrewslai.com/static s3://kaleidoscope.client/static --delete --exclude "*/shareable_stories/*" --dryrun`
Expected: a plausible upload list including `static/favicon.ico` and `static/images/...`; no `media/` keys.

- [ ] **Step 5: Commit (in kaleidoscope-ui).**

```bash
git add scripts/deployment/deploy-kaleidoscope-client scripts/deployment/deploy-ephemeral resources/andrewslai.com/static/favicon.ico
git commit -m "feat(deploy): ship andrewslai static chrome to the shared kaleidoscope.client store

- prod + ephemeral deploys sync resources/andrewslai.com/static to the client
  store (prod bucket / eph-<slug>/static prefix), matching the backend's new
  single-static-store routing; add a root static/favicon.ico for the route"
```

---

## Task 3 (backend): Remove the fixture-based `seed-tenant-assets`

**Files:**
- Delete: `scripts/ephemeral/seed-tenant-assets`, `test-resources/ephemeral-sample-assets/` (whole tree)
- Modify: `scripts/ephemeral/up`, `scripts/ephemeral/deploy-app`, `Taskfile.yml`, `docs/operations.md`

**Interfaces:**
- Consumes: Task 2 (the frontend now supplies static, so seeding is obsolete).
- Produces: ephemeral `up` no longer seeds. `KALEIDOSCOPE_TENANT_ASSET_BUCKET/_PREFIX` and the `ephemeral-tenant-assets` adapter **stay** — they still back `/media/*`'s per-tenant `:asset-store` (now unseeded, so `/media/*` article images 404 in ephemeral: the deferred article-asset gap, not a regression here).

- [ ] **Step 1: Drop the seed step from `up`.** In `scripts/ephemeral/up`, remove the `"$EPHEMERAL_DIR/seed-tenant-assets" --name="$SLUG"` line and update the orchestration comment (`provision-db -> build-frontend -> deploy-app -> smoke-test -> checkly-test`; note the frontend now ships `/static/*`).

- [ ] **Step 2: Drop the static-asset favicon guard in `deploy-app`.** Remove the block that `aws s3 ls`-checks `${ASSET_PREFIX}static/favicon.ico` and warns to run `seed-tenant-assets` (it checked the now-removed seed path; static is served from the client bucket, already guarded by the `index.html` check). Leave the `KALEIDOSCOPE_TENANT_ASSET_BUCKET/_PREFIX` secrets in place (they still wire `/media/*`).

- [ ] **Step 3: Delete the seed script + fixtures.**

```bash
git rm scripts/ephemeral/seed-tenant-assets
git rm -r test-resources/ephemeral-sample-assets
```

- [ ] **Step 4: Remove the Taskfile tasks.** Delete the `env:seed-tenant-assets` and `ephemeral:seed-tenant-assets` task blocks from `Taskfile.yml`. Verify the file still parses:

Run: `task --list`
Expected: no error; `seed-tenant-assets` no longer listed.

- [ ] **Step 5: Update `docs/operations.md`.** In "Ephemeral tenancy / asset isolation", replace the `/static/*` seeding bullet with: static chrome is served from the shared `kaleidoscope.client` store, populated by the frontend deploy (no seeding); `/media/*` article images still use the per-tenant `:asset-store` and are unseeded in ephemeral pending the `article-embedded-asset-acl` plan.

- [ ] **Step 6: Commit.**

```bash
git add scripts/ephemeral/up scripts/ephemeral/deploy-app Taskfile.yml docs/operations.md
git commit -m "chore(ephemeral): retire seed-tenant-assets — static now shipped by the frontend

- drop the seed step from up, the fixtures, both Taskfile tasks, and the
  deploy-app static favicon guard
- keep KALEIDOSCOPE_TENANT_ASSET_* (still backs /media/*'s per-tenant asset-store)
- docs/operations.md updated"
```

---

## Task 4 (frontend, kaleidoscope-ui): Delete redundant per-tenant static

**Files:**
- Delete: `resources/caheriaguilar.com/`, `resources/sahiltalkingcents.com/`, `scripts/deployment/deploy-caheriaguilar-to-s3`, `scripts/deployment/deploy-sahiltalkingcents-to-s3`, `scripts/deployment/deploy-to-andrewslai`

**Interfaces:**
- Consumes: Tasks 1–3 (all tenants now serve andrewslai's static from the shared store).
- Produces: one canonical static tree (`resources/andrewslai.com/static/`) and one deploy path (`deploy-kaleidoscope-client`).

- [ ] **Step 1: Confirm nothing else references the deleted folders/scripts.**

Run: `grep -rnE "resources/(caheriaguilar|sahiltalkingcents)|deploy-(caheriaguilar|sahiltalkingcents|to-andrewslai)" . --include=*.ts --include=*.js --include=*.json --include=*.sh`
Expected: only the files being deleted (and maybe `package.json` scripts — remove those entries too). If a build config imports from those folders, stop and reconcile before deleting.

- [ ] **Step 2: Delete the per-tenant folders + scripts.**

```bash
git rm -r resources/caheriaguilar.com resources/sahiltalkingcents.com
git rm scripts/deployment/deploy-caheriaguilar-to-s3 scripts/deployment/deploy-sahiltalkingcents-to-s3 scripts/deployment/deploy-to-andrewslai
```

Remove any `package.json` script entries that invoked them.

- [ ] **Step 3: Verify the SPA still builds** (nothing imported the deleted assets).

Run: `npm run build`
Expected: build succeeds; `resources/kaleidoscope.client/static/dist/index.html` exists.

- [ ] **Step 4: Commit (in kaleidoscope-ui).**

```bash
git add -A
git commit -m "chore: delete redundant per-tenant static assets + deploy scripts

- andrewslai.com/static is the single canonical static set (served from the
  shared client store); caheriaguilar.com/ and sahiltalkingcents.com/ folders
  and their per-tenant deploy scripts are removed"
```

---

## Task 5 (backend): Documentation — record the single-static-store architecture

**Files:**
- Modify: `docs/operations.md`, `CLAUDE.md`

- [ ] **Step 1: `CLAUDE.md`.** In the Architecture / multi-tenant description, add a sentence: static chrome (`/static/*`, `/favicon.ico`) is served from one shared `kaleidoscope.client` store for all tenants; multi-tenancy is enforced by the DB `hostname` scoping and `themes.config`, not by per-tenant static buckets. `/media/*` article images remain per-tenant (see the article-asset plan).

- [ ] **Step 2: `docs/operations.md`.** Ensure the "Media object storage" / ephemeral sections and the per-tenant-bucket retirement guard read coherently with the new static model: per-tenant prod buckets are still needed **only** for `/media/*` now (static moved off them), which tightens — but does not remove — the retirement guard.

- [ ] **Step 3: Commit.**

```bash
git add CLAUDE.md docs/operations.md
git commit -m "docs: record the single shared static asset store; tenancy in DB, not buckets"
```

---

## Decisions

1. **Shared store = `kaleidoscope.client` (per the request).** The SPA (`/`, `/assets/*`) already serves from it; static rides alongside under `static/`. Alternative considered: `kaleidoscope.pub` (the existing "shared static assets" bucket). Either works; `kaleidoscope.client` chosen because the frontend already deploys there and the SPA + its static chrome are one artifact. Revisit only if you want static on a separately-cached/CDN'd bucket.
2. **`/media/*` is out of scope (Option A).** Article-embedded images keep the per-tenant `:asset-store`; their storage move to `kal-media-prod` + access-control model is the `article-embedded-asset-acl` plan. Consequence: `/media/*` article images 404 in ephemeral (unseeded) until that plan — an accepted, pre-existing gap, not introduced here.
3. **Per-tenant prod buckets stay.** Static moved off them, but `/media/*` still reads article images from them — do **not** delete them (the `docs/operations.md` retirement guard still holds, now scoped to `/media/*` only).
4. **caheriaguilar.com's divergent layout is discarded, not migrated.** Its `resources/caheriaguilar.com/` used a different layout (`css/`, `images/nav-bar/`) and a cherry-pick deploy. Per "andrewslai is the source of truth," it converges on andrewslai's static — its distinct favicon/nav assets are dropped. **Confirm this is acceptable** (any caheriaguilar-specific chrome that must survive should move into `themes.config` or a shared asset before Task 4 deletes the folder).
5. **`static/favicon.ico` key.** The `/favicon.ico` route fetches `static/favicon.ico`; the canonical assets currently store the favicon at `static/images/nav-bar/favicon.{png,svg}`. Task 2 Step 1 adds a `static/favicon.ico` so the route resolves. Alternative: change the route's `:uri` to the real favicon key — rejected to keep the route stable and avoid touching browsers' default `/favicon.ico` fetch.

## Self-Review

- **Spec coverage:** single shared static store (Task 1) ✅; frontend ships canonical static to prod + ephemeral, favicon key (Task 2) ✅; retire fixture seeding + Taskfile tasks + fixtures (Task 3) ✅; delete redundant per-tenant folders/scripts (Task 4) ✅; `/media/*` explicitly untouched + per-tenant buckets retained (Global Constraints, Decisions 2–3) ✅; docs (Task 3 Step 5, Task 5) ✅.
- **Placeholder scan:** test code, route edits, and sync commands are concrete; no "TBD"/"handle errors" placeholders. The one runtime-supplied value is `<slug>` (operator-supplied), consistent with existing scripts.
- **Type/name consistency:** the store key `"kaleidoscope.client"` matches the existing `/` and `/assets/*` routes and the env.clj adapter map key; `:store` is read by `wrap-resolve-tenant` (unchanged); `(:store adapter)` accesses the MemFS atom set by `make-mem-fs`; `in-mem/file` matches `in_memory_impl.clj`.
- **Cross-repo ordering:** Task 2 (frontend ships static) must reach an environment before Task 1 (backend routes to it) serves traffic there, or `/static/*` 404s — called out in Global Constraints and the execution note.
