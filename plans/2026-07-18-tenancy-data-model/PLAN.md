# Tenancy data-model fixes

Corrected model (owner confirmed 2026-07-18): **`hostname` is the tenant axis for EVERY table.**
`user_id` is a *secondary owner dimension within a tenant*, not an alternative to tenancy. The AI
engine (projects/workflows/tasks/scores/agents/interests/…) was prototyped with AI without a
hostname — that is a bug, not a second axis. Every app table must be hostname-scoped.

The gaps therefore fall in two groups:
- **CMS site-axis gaps** (albums ✅ done, portfolio, article/photo child hardening).
- **AI-engine tenancy — entirely missing** (all owner-scoped tables need hostname added).

Gold-standard pattern already in the codebase: **recipes** — `hostname NOT NULL`,
`UNIQUE (id, hostname)` on parents, and `FOREIGN KEY (child_id, hostname) REFERENCES parent (id, hostname)`
on children, so the DB itself forbids cross-tenant attachment. Make every site-axis table look like this.

## Decisions (confirmed with owner 2026-07-18)
- **albums** → site-scoped. Add `hostname`, backfill existing → `andrewslai.com`.
- **portfolio_entries / portfolio_links** → site-scoped. Add `hostname`, backfill → `andrewslai.com`.
- **article/photo child tables** → harden to the recipes composite-FK pattern.

## Slices (each = migration up/down + view regen + code scoping + tenant-set + cross-tenant test; run H2 **and** embedded-pg)

1. **albums** (self-contained; validates the pattern)
   - `ALTER TABLE albums ADD hostname`; backfill `andrewslai.com`; `NOT NULL`; `UNIQUE (id, hostname)`.
   - Regenerate `enhanced_albums` (SELECT a.* → picks up hostname) and `album_contents` (add `a.hostname`).
   - Scope `get-albums` handlers (album.clj) via `tenant/scope`.
   - Add `albums`, `enhanced_albums`, `album_contents` to `tenant/tenant-scoped-tables`.

2. **portfolio** (entries + links)
   - Add `hostname` to both; backfill `andrewslai.com`; `NOT NULL`.
   - `portfolio_links` refs entries by NAME → hostname keeps the name-graph within a site.
   - Scope `get-portfolio`. Add both to `tenant-scoped-tables`.

3. **article child hardening** (composite FKs)
   - Parents get `UNIQUE (id, hostname)`: `articles`, `tags`, `article_branches` (for versions).
   - Children get `hostname` + backfill-from-parent + composite FK:
     `article_branches`→articles, `article_versions`→article_branches, `article_tags`→articles+tags,
     `article_audiences`→articles.  (`group_id`→groups stays; groups is owner-axis.)

4. **photo child hardening**
   - `photos` gets `UNIQUE (id, hostname)`.
   - `photo_versions` + `photos_in_albums` get `hostname` + backfill + composite FK to `photos(id,hostname)`
     (and `photos_in_albums`→`albums(id,hostname)` from slice 1).

## Guardrails
- `tenant-scoped-tables-match-schema-test` is the tripwire: every new `hostname` column MUST be added to
  the set or the test fails. That is the forcing function.
- Backfill (UPDATE) MUST run before `SET NOT NULL` / composite FK, in the same migration.
- Both embedded-h2 and embedded-pg run migrations in tests → dialect issues surface immediately.

## AI-engine tenancy (NEW — the big one; all tables need hostname)
Root aggregates get their own `hostname` (+ `UNIQUE (id, hostname)`); children inherit via composite
`(parent_id, hostname)` FK, recipes-style. Existing rows have NO tenant signal → backfill to a chosen
default (OPEN DECISION — see below). Persistence layer is raw `next.jdbc` (must thread hostname; already
made TenantConn-unwrap-safe earlier, but these fns take explicit args → add hostname to WHERE/inserts).

- **Root (own hostname):** `projects`, `workflows`, `score_definitions`, `agent_definitions`,
  `interests`, `user_workspace_roots`. (`groups` too — decide: per-site sharing groups.)
- **Transitive (composite FK to a root):** `workflow_steps`, `project_workflow_runs`,
  `project_workflow_step_runs`, `project_score_runs`, `project_score_dimensions`,
  `score_dimension_definitions`, `project_notes`, `project_conversations`, `project_skills`,
  `workflow_rounds`, `workflow_judge_records`, `project_briefs`, `project_task_generation_runs`,
  `project_tasks`, `task_artifacts`, `recommendations`.

### DECISIONS (confirmed 2026-07-18)
- Backfill all existing AI-engine rows → `hostname = 'andrewslai.com'` (primary site).
- `groups` + `user_group_memberships` become hostname-scoped (per-site groups).

## Status
- ✅ Slice 1 albums — `20260718000001` (reads scoped, create/update IDOR-hardened, tenant/scope nil-safe).
- ✅ Slice 2 portfolio — `20260718000002` (hostname on entries+links, scoped read).
- ✅ Slice 3 article children — `20260718000003` (composite (id,hostname) FKs; DB now forbids
  cross-tenant branch/version/tag/audience attachment; create-branch!/version!/audience threaded).
- ✅ Slice 4 photo children — `20260718000004` (photos UNIQUE(id,hostname); photo_versions +
  photos_in_albums composite FKs; make-image-version/add-photos-to-album! threaded).
- ✅ Slice 5 groups — `20260718000005` (groups + user_group_memberships per-site; full_memberships
  view carries hostname; create/add-users threaded; keystone tests updated).
- All verified on H2 **and** embedded-pg; full suite 0 failures (only the pre-existing pg-initdb flake).

### Slices 6–7 AI engine — IN PROGRESS (foundation laid, green)
Keystone: `rdbms/insert!`, `scoped-update!`, `scoped-delete!` now inject hostname for tenant-scoped
tables when handed a scoped handle (via `tenant/inject-row`/`scope-query`) — so a scoped handle
auto-stamps writes, mirroring finder reads. `tenant/hostname-of` added. Verified slices 1–5 unaffected.

- ✅ Migration `20260718000006` — nullable `hostname` + backfill `andrewslai.com` on ALL 22 AI-engine
  tables; all 22 added to `tenant-scoped-tables` (tripwire green). Full suite green. SAFE/additive:
  nothing requires hostname yet, so the engine keeps working while code is threaded.
- ✅ `workspace_roots` domain threaded (3 handlers scoped; finder/insert only, no raw reads) — proves
  the pattern: scope the handler's db → keystone stamps writes + finder reads auto-scope.

### Remaining (precise, from the 2 inventory agents)
Per domain: scope handlers to a `tenant/scope`d db, and for RAW `next/execute!` reads (which crash on a
TenantConn) unwrap + add `[:= :hostname h]`. Then a final migration enforces.
- **agents** (raw: seed-default ON CONFLICT needs `,hostname`), **score_definitions** (2 raw reads +
  seeder), **interests**/recommendations (2 raw reads; create-interest! delegates to create-project!),
  **tasks** (4 raw reads), **projects** (~7 raw reads), **workflows** (~18 raw reads) — the big two.
- 3 runtime seeders thread hostname: `seed-default-workflows!`, `seed-default-agent-definitions!`
  (+ ON CONFLICT target), `seed-default-definitions!`.
- ~30 raw reads: unwrap + hostname filter. ~50 handlers: scope db. ~60 test fixtures (http_api tests
  just need a Host header; api/persistence tests need a scoped handle or explicit hostname).
- **Final migration (7):** SET NOT NULL, UNIQUE(id,hostname) on roots, composite (parent_id,hostname)
  FKs on the 16 children, and `hostname` into agent_definitions/user_workspace_roots business uniques.

## Sequencing recommendation
1. Finish CMS (portfolio, article/photo hardening) — mechanical, pattern proven.
2. groups — small, unblocks nothing but consistent.
3. AI engine — the large block; do roots first (projects/workflows/score_definitions/agent_definitions/
   interests/user_workspace_roots) with backfill, then transitive children via composite FK, threading
   hostname through the raw-jdbc persistence layer + all AI-engine tests. Backfill = 'andrewslai.com'.
