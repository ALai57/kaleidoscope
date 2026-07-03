# Multi-tenant ownership consolidation

Date: 2026-07-02

## Problem

Ownership ("does this user own this row") is checked with **three different
idioms** across the codebase, and the in-flight WIP diff on this branch
(`score_definitions.clj`, `workflows.clj`) is about to add a fourth variant of
the same idea. The stated goal is to make "the system always behaves in a
multi-tenant way" structural — not something a developer has to remember to
check per call site.

### Pattern A — SQL-filtered fetch, unscoped mutation (`projects.clj`)

```clojure
(defn get-project [db project-id user-id]
  (first (get-projects-raw db {:id project-id :user-id user-id})))
```

`get-projects-raw` is `rdbms/make-finder`, which builds a
`WHERE id=? AND user_id=?` query — ownership enforced in SQL, non-owner and
not-found are indistinguishable. **However**, `update-project!` and
`delete-project!` accept a `user-id` argument and never use it — the actual
`UPDATE`/`DELETE` only keys off `:id`. Ownership for writes is enforced solely
by the caller remembering to call the scoped `get-project` first. This is a
latent trust-boundary gap: a future direct caller of `update-project!`/
`delete-project!` would silently mutate any user's row.

### Pattern B — fetch-then-compare in the API layer (current WIP)

`api/score_definitions.clj`, `api/workflows.clj` (and `api/agents.clj`):

```clojure
(defn get-workflow [db user-id workflow-id]
  (when-let [wf (persistence/get-workflow db workflow-id)]
    (when (= (:user-id wf) user-id)
      wf)))
```

`persistence/get-workflow` / `persistence/get-score-definition` don't filter
by user at all — the check is hand-rolled in Clojure, three times per file
(get/update/delete), and easy to forget on a new call site.

### Pattern C — dedicated `owns?` helper, duplicated per domain

`api/groups.clj` and `api/themes.clj` each define their own:

```clojure
(defn owns? [database requester-id resource-id]
  (= requester-id (-> database (get-X {:id resource-id}) first get-owner)))
```

Nearly identical code, and a different field name (`:owner-id` instead of
`:user-id`) from every other domain.

### Why this matters

Three idioms means three places a fix/extension can be applied inconsistently,
and a real gap already exists (Pattern A's unscoped mutation). The ask is to
converge on one pattern that makes "forgetting the check" structurally
impossible rather than a matter of code review discipline.

## Call-site inventory (from survey)

| File | Resource | Pattern |
|---|---|---|
| `api/projects.clj` (13 sites) | project | A |
| `api/tasks.clj` (6 sites) | project | A |
| `api/workflows.clj` (11 sites, project/run-scoped ops) | project | A |
| `api/workflows.clj` (get/update/delete-workflow) | workflow | B |
| `api/score_definitions.clj` (get/update/delete) | score-definition | B |
| `api/agents.clj` (update-agent-definition!) | agent-definition | B |
| `api/groups.clj` (`owns?`) | group | C |
| `api/themes.clj` (`owns?`) | theme | C |

Roughly 30+ call sites across 7 files.

## Identity threading (unaffected by this change)

`http_api/middleware.clj`'s `wrap-classify-identity` merges
`api/authentication.clj`'s `classify-identity` output into `:identity`:
`{:type :verified-user|:service-account|:unverified-user, :user-id ...,
:roles #{...}}`. All handlers read `(:user-id (:identity request))` — this
stays as-is; it's a separate concern (authentication/role ACL, handled by
`api/authorization.clj`'s `require-*-writer`/`reader`/`admin`) from resource
ownership (this plan).

## Proposed design

### 1. Generalize the primitive in `persistence/rdbms.clj`

Add scoped mutation helpers alongside the existing `make-finder`:

- `scoped-update!` — takes a where-map (e.g. `{:id id :user-id user-id}`)
  instead of a bare id, builds `WHERE id=? AND <owner-col>=?` via HoneySQL,
  returns the affected row(s) so callers can distinguish "0 rows" (not found
  or not owned) from success.
- `scoped-delete!` — same shape for delete.

`make-finder` already supports scoped reads via a query map; no change needed
there beyond using it consistently everywhere.

This is the load-bearing fix: it closes Pattern A's real gap (unscoped
`update!`/`delete!`) by making ownership enforcement part of the SQL
statement itself, not a preceding check that can be skipped.

### 2. Uniform signature shape across every owned resource

`get-X db id user-id`, `update-X! db id user-id updates`,
`delete-X! db id user-id` — applied to projects (fix the dead param),
workflows, score-definitions, groups, and themes. Groups/themes drop their
hand-rolled `owns?` + `get-owner` and call the same `scoped-update!`/
`scoped-delete!` as everyone else, passing `:owner-id` as their scoping key
(see open question below on whether to normalize the field name).

### 3. API layer becomes a pass-through, not a checker

Fix the current WIP in `api/score_definitions.clj` and `api/workflows.clj`:
delete the `(when (= (:user-id defn) user-id) ...)` blocks. Once the
persistence call itself is scoped, "not owned" and "not found" collapse into
the same `nil`/no-op result — there's no comparison left to forget.

### 4. HTTP layer unchanged

Handlers keep threading `(:user-id (:identity request))` into each api call,
same as today — this layer was never the problem.

### 5. Tests

Add a "cross-tenant" assertion per resource type — user B's requests against
user A's project/workflow/score-definition/group/theme all return
not-found — so the multi-tenant guarantee has explicit automated coverage,
not just implicit trust in the WHERE clause.

## Open question: field naming

`groups`/`themes` use `:owner-id`; `projects`/`workflows`/
`score-definitions` use `:user-id`. Two options:

- **Normalize to `:user-id` everywhere** — requires a migration renaming the
  `owner_id` columns in `groups`/`themes` tables, plus updating any
  referencing code/tests. Lets one generic helper work across all domains
  with zero per-domain configuration.
- **Leave `:owner-id` as-is, parameterize the helper** — no migration
  needed; the shared `rdbms` helper takes an explicit owner-column argument
  per domain.

Recommendation: defer the rename — parameterize the helper now, revisit the
schema rename separately since it's an unrelated schema change bundled into
an auth refactor otherwise.

## Suggested sequencing

1. Fix the in-flight WIP (`score_definitions.clj`, `workflows.clj`) to use
   the new scoped-mutation primitive instead of the fetch-then-compare
   pattern it currently adds.
2. Extend `persistence/rdbms.clj` with `scoped-update!`/`scoped-delete!`.
3. Migrate `projects.clj` (fix the dead `user-id` param in
   `update-project!`/`delete-project!`).
4. Migrate `groups.clj`/`themes.clj` onto the same primitive, deleting their
   `owns?`/`get-owner` helpers.
5. Add cross-tenant regression tests per resource.
