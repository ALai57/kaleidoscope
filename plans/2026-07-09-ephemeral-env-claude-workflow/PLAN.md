# Finishing the ephemeral-env deploy scripts + Claude Code workflow

Companion to `plans/2026-07-03-ephemeral-fly-environment/PLAN.md`. That plan designed the whole
system; the app-code phases (3, 4, `role-domain` authz, `KALEIDOSCOPE_ENV` tagging) already landed
on the `ephemeral-env` branch. **What's left is the orchestration glue (Phases 2 + 5) and the
Claude Code setup so one command / one prompt builds and pushes *both* repos.**

Two repos are in play:
- Backend: `/Users/alai/code/kaleidoscope` (this repo, primary working dir).
- Frontend: `/Users/alai/code/kaleidoscope-ui` (sibling; no `CLAUDE.md`, no `.claude/` yet).

---

## Core architecture decision: two layers, kept separate

1. **Deterministic deploy scripts (the hot path).** `scripts/ephemeral/*` are plain shell — no AI
   in the loop. They must run identically from a terminal, from a Claude session, and from GitHub
   Actions later (Phase 6). The frontend build is just `cd ../kaleidoscope-ui && npm ci && <build>`
   inside `build-frontend`.
2. **Frontend subagent (an authoring/iteration helper on top).** A `frontend-deployer` subagent
   that operates with the UI repo's *own* conventions (its `CLAUDE.md` + skills) for when you ask
   Claude to "change the frontend and redeploy." It delegates UI-repo edits/build, then the
   deterministic script does the push. It is **not** on the deterministic deploy path.

This keeps the pipeline reproducible while still giving you a single-prompt Claude workflow.

---

## Claude Code wiring (answers "one place to define frontend instructions")

Verified against Claude Code docs:
- A subagent's working dir is always the **main session's cwd** (backend). `cd` does not persist
  between its Bash calls → every UI shell step must be self-contained.
- An added directory loads config **only via `--add-dir` / `/add-dir`**, not via the
  `permissions.additionalDirectories` settings key (that grants file access only).
- From an `--add-dir` directory: `.claude/skills/` loads (live reload), `.claude/agents/` loads,
  but `CLAUDE.md` loads **only when `CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1`** is set.

**Setup:**
1. Create `../kaleidoscope-ui/CLAUDE.md` — single source of truth for frontend conventions
   (build command, env matrix, deploy targets, test commands).
2. Create `../kaleidoscope-ui/.claude/skills/` for any frontend-specific skills (e.g. a
   `build-and-sync` skill).
3. Add a `frontend-deployer` subagent at `.claude/agents/frontend-deployer.md` in *this* repo.
   Body instructs it to operate in `../kaleidoscope-ui` with absolute/`cd`-prefixed commands.
   Optionally use the `skills:` frontmatter field to preload UI skills.
4. Launch convention — bake into a shell alias or `.envrc`:
   ```bash
   CLAUDE_CODE_ADDITIONAL_DIRECTORIES_CLAUDE_MD=1 claude --add-dir ../kaleidoscope-ui
   ```
5. Permissions allowlist in `.claude/settings.local.json` (extend the existing `fly logs`/`fly
   machine`/`./bin/test` entries) so the flow doesn't prompt each step:
   - `Bash(task env:up *)`, `Bash(task env:down *)`
   - `Bash(fly deploy *)`, `Bash(fly apps *)`, `Bash(fly secrets *)`
   - `Bash(neonctl *)`
   - `Bash(aws s3 *)`
   - `Bash(cd ../kaleidoscope-ui && npm *)` (or add `../kaleidoscope-ui` npm rules)
   - Keep secrets out of reach: a `Read` deny rule on `.env.fly.staging` / `**/.env*`.

---

## Work items

### A. Frontend build chain — RESOLVED (2026-07-09)
Traced in `../kaleidoscope-ui`:
- Build: `npm run build` (= `vite build`). No `build-release` script exists (README is stale — it
  still describes the old ClojureScript app).
- Output: `resources/kaleidoscope.client/static/dist/` — only `index.html` + hashed `assets/*`
  (`emptyOutDir: true`, `copyPublicDir: false`). The old plan's `resources/kaleidoscope.pub/static`
  assumption was wrong; `.pub`/`.com` dirs hold per-site *static assets*, not the Vite bundle.
- Deploy today: `npm run deploy` (`deploy-kaleidoscope-client`) does `npm version patch` then syncs
  `dist/assets` → `s3://kaleidoscope.client/assets --delete` and `dist/index.html` →
  `s3://kaleidoscope.client/index.html`.

**So `build-frontend` = `cd ../kaleidoscope-ui && npm ci && npm run build`, then sync
`resources/kaleidoscope.client/static/dist/{assets,index.html}` to the ephemeral target.**

**NEW GAP surfaced — verified against code 2026-07-09.** Two distinct static-serving paths:
  1. **SPA shell** — `/`, `/index.html`, `/assets/*` (`kaleidoscope.clj` `reitit-index-routes`)
     carry `:host "kaleidoscope.client"` **hardcoded in route data**, pinning them to the adapter
     under the fixed key `"kaleidoscope.client"` (`env.clj:216`), regardless of request Host.
  2. **Per-host static** — `/static/*`, `/media/*` have no `:host`, so they resolve via the real
     Host through `static-content-adapters`. This is what the `EPHEMERAL_HOST_*` alias and the
     `db-backed-multi-tenancy` plan feed.
  Both go through the same adapter map (`http_utils/get-resource`); #1 just always looks up the
  fixed key. So syncing the built bundle to `kal-ephemeral/eph-<slug>/` does **not** change what `/`
  serves — the `EPHEMERAL_HOST_*` alias only touches path #2.

  **Decision (2026-07-09): configure the shell bucket via an environment variable.** Which bucket
  serves the frontend is a per-*environment* infra concern — the same category as every other
  `KALEIDOSCOPE_*` var in `env.clj`/`fly.toml` — not per-branch DB data. This keeps env config in env
  config, decouples the ephemeral frontend story from `db-backed-multi-tenancy` entirely, and mirrors
  the already-shipped `KALEIDOSCOPE_EPHEMERAL_HOST_*` overlay. (The DB-registry-row override was
  considered and rejected: it conflates infra config with tenant data and would couple this to an
  unrelated plan.) This makes serving a *modified* frontend per env in-scope, not a deferred follow-on.
  See item **A2** for the code change.

### A2. Env-var override for the shell (`kaleidoscope.client`) bucket — DONE (2026-07-09)
Implemented: `env.clj` `"kaleidoscope.client"` adapter reads `KALEIDOSCOPE_CLIENT_BUCKET`/`_PREFIX`
(defaults to today's literal, no-op for prod); unit test
`s3-static-content-launcher-overrides-client-shell-bucket-when-configured` in `env_test.clj`. Focused
suite green (5 tests, 19 assertions, 0 failures). Design notes below.

Make the `"kaleidoscope.client"` adapter in `kaleidoscope-static-content-adapter-boot-instructions`
(`env.clj:216`) read its bucket/prefix from env vars, defaulting to today's literal when unset
(no-op for prod). Same `cond->` shape as the existing `EPHEMERAL_HOST_*` overlay right below it —
adapt that pattern, don't fork a new one.
- New vars: `KALEIDOSCOPE_CLIENT_BUCKET` (default `"kaleidoscope.client"`) and
  `KALEIDOSCOPE_CLIENT_PREFIX` (optional). Naming is the user's call; these read naturally against
  the `kaleidoscope.client` bucket. Sketch:
  ```clojure
  "kaleidoscope.client"
  (s3-storage/make-s3 (cond-> {:bucket (or (get env "KALEIDOSCOPE_CLIENT_BUCKET") "kaleidoscope.client")}
                        (get env "KALEIDOSCOPE_CLIENT_PREFIX")
                        (assoc :prefix (get env "KALEIDOSCOPE_CLIENT_PREFIX"))))
  ```
- Covers `/`, `/index.html`, and `/assets/*` in one shot — all three key on `"kaleidoscope.client"`.
- Per-host static (`/static/*`, `/media/*`) stays configured by the existing `EPHEMERAL_HOST_*`
  vars, so the whole static-serving story is env-configurable and consistent.
- `make-s3`'s `:prefix` already shipped (Phase 4) — no persistence change needed.
- **Verify:** unit test on the boot-instructions fn — vars set → the `"kaleidoscope.client"` adapter
  targets the given bucket/prefix; unset → identical to today. `task test` otherwise unaffected
  (no-op by construction). `kaleidoscope.clj` route data is **not** touched.

### B. Phase 2 — scripted backend-only `env:up` / `env:down` — DONE (2026-07-09)
Built and locally validated (bash `-n`, shellcheck clean, pure helpers + `fly.toml` transform +
Taskfile parse all verified). **Not yet run end-to-end** — that needs the one-time prereqs below
(Neon `staging` branch, `.env.fly.staging`, Auth0 `ephemeral:*` role) and creates real cloud
resources, so it's the user's to run.
- `scripts/ephemeral/lib.sh` — slug from `--name=`/git branch (lowercased, non-alnum→`-`, ≤20);
  derives `kal-eph-<slug>` / `eph-<slug>` / OTEL name / `eph-<slug>/` prefix; Neon helpers +
  connection-string parser; loads `.env.fly.staging`.
- `scripts/ephemeral/provision-db` — `neonctl branches create --parent staging` (idempotent),
  parse connection string, migrate via the same `clojure -A:dev -M -m ...migrations migrate` call.
- `scripts/ephemeral/deploy-app` — `task build:uberjar`, generate per-run `fly.toml` via `sed`
  (swap app name + `OTEL_SERVICE_NAME`, **delete** prod DB `HOST`/`NAME`/`USER` and
  `KALEIDOSCOPE_ENV` `[env]` lines so per-branch secrets are authoritative — avoids ambiguous
  secret-vs-`[env]` precedence, which otherwise re-tags ephemeral errors as `production`;
  scale-to-zero via `auto_stop_machines="suspend"` + `min_machines_running=0`), `fly apps create`
  (idempotent), `fly secrets set --stage` (branch DB, `KALEIDOSCOPE_ENV`, Bugsnag, AWS),
  `fly deploy`. Phase-5 frontend secrets marked with an inline hook.
- `scripts/ephemeral/smoke-test` — `/ping` 200, `/` 200, and (when `AUTH0_CLIENT_ID/SECRET` set)
  M2M token → `GET /projects` 200 with token / 401 without. Models `scripts/test-m2m-token`.
- `scripts/ephemeral/up` / `down` — orchestrate; `up` = provision-db → deploy-app → smoke-test,
  `down` = destroy Fly app + delete Neon branch (best-effort). Phase-5 hooks marked inline.
- `Taskfile.yml`: `env:up` / `env:down` (NAME defaults to git branch).

### C. Phase 5 — frontend build + full integration — DONE (2026-07-09)
Built and locally validated (bash `-n`, shellcheck clean, `FRONTEND_DIR` resolves). **Not yet run
end-to-end** — needs the `kal-ephemeral` bucket + scoped AWS creds in `.env.fly.staging`, so it's
the user's to run. Delivered:
- `scripts/ephemeral/build-frontend` — `npm ci && npm run build` in `$FRONTEND_DIR`, then
  `aws s3 sync resources/kaleidoscope.client/static/dist/ s3://kal-ephemeral/eph-<slug>/ --delete`.
  Asserts `dist/index.html` exists (guards against a vite outDir change). Skips `npm version patch`.
- `deploy-app` — sets `KALEIDOSCOPE_CLIENT_BUCKET/_PREFIX` (shell) and `KALEIDOSCOPE_EPHEMERAL_HOST_*`
  (per-host static) as secrets. All new keys, no `[env]` conflict → no `sed` deletion needed (per the
  env-config convention). Header comment updated; no longer "backend only".
- `up` — now `provision-db → build-frontend → deploy-app → smoke-test` (build before deploy so the
  SPA is in S3 before the app boots / the first `/` request lands).
- `down` — now also `aws s3 rm s3://kal-ephemeral/eph-<slug>/ --recursive` (bucket left intact).
- `smoke-test` — its existing `/ → 200` check already asserts the SPA shell resolves (not a 404).
- Known limitation: `build-frontend` syncs only the SPA bundle; per-host `/static/*`, `/media/*`
  image/css assets aren't populated under the prefix, so those may 404 (SPA still renders). Left as a
  follow-on if a run shows it matters.

Original design notes:
- `scripts/ephemeral/build-frontend` — `cd ../kaleidoscope-ui && npm ci && npm run build`, then
  `aws s3 sync resources/kaleidoscope.client/static/dist/ s3://kal-ephemeral/eph-<slug>/`. This lands
  `index.html` at `eph-<slug>/index.html` and the bundle at `eph-<slug>/assets/*` — exactly the
  layout the prefixed `kaleidoscope.client` adapter (item A2) fetches (`get-resource` prepends the
  prefix to the S3 Key). Skip the `npm version patch` that `deploy-kaleidoscope-client` does — that's
  a prod-release concern, not wanted per ephemeral run.
- Extend `deploy-app` to set, as Fly env/secrets:
  - `KALEIDOSCOPE_CLIENT_BUCKET=kal-ephemeral`, `KALEIDOSCOPE_CLIENT_PREFIX=eph-<slug>/` (item A2 —
    makes `/`, `/index.html`, `/assets/*` serve the ephemeral build).
  - `KALEIDOSCOPE_EPHEMERAL_HOST_ALIAS=<fly-hostname>`, `_BUCKET=kal-ephemeral`,
    `_PREFIX=eph-<slug>/` (already consumed by `init/env.clj`) — per-host `/static/*`, `/media/*`.
- Extend `up` to call `build-frontend`; `down` to `aws s3 rm s3://kal-ephemeral/eph-<slug>/ -r`.
- Extend `smoke-test` `/` to assert the built SPA is served, not a 404.
- **Depends on item A2 landing** (the shell env-var override) — that's now the mechanism that makes
  a per-env frontend build actually reachable at `/`.

### D. Frontend subagent + UI-repo config (the Claude layer)
- `../kaleidoscope-ui/CLAUDE.md`, `../kaleidoscope-ui/.claude/skills/*`.
- `.claude/agents/frontend-deployer.md` in this repo.
- Launch alias + permissions (see "Claude Code wiring" above).

---

## Still-open third-party prerequisites (one-time, not scripted)
- Neon: persistent `staging` branch forked from `main`.
- AWS: reuse prod creds (Phase 2); create `kal-ephemeral` bucket + scoped creds (Phase 4/5).
- Auth0: `ephemeral:writer`/`:admin` role assigned to the M2M test client; wildcard callback URL
  `https://*.fly.dev/*` for interactive login.
- `.env.fly.staging` (local, untracked): `NEON_API_KEY`, `NEON_PROJECT_ID`, AWS creds.

## Explicitly out of scope
- GitHub Actions per-PR automation (Phase 6 — build the CLI scripts first so CI reuses them).
- Real Anthropic key (opt-in; prod runs AI mocked today).
- Sumo Logic shipping for ephemeral apps (`fly logs -a <app>` instead).
