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

### Audit gap found during review (fixed 2026-07-03)

A pre-implementation review of this plan turned up a fourth case that doesn't
fit any of the three named patterns: `api/themes.clj`'s `update-theme!` had
**no ownership check at all** — not fetch-then-compare, not `owns?`, nothing.
`delete-theme!` in the same file correctly gates on `owns?`, but
`update-theme!` didn't, and the HTTP handler (`http_api/themes.clj` `PUT
/themes/:theme-id`) called it directly with the requester's own `owner-id`
merged into the row. Since `rdbms/update!` keys only off `:id`, this wasn't a
"future caller might forget" hazard like Pattern A — it was live: any
authenticated user could overwrite any other user's theme by guessing/
enumerating a `theme-id`, and the update would silently reassign the theme's
`owner-id` to themselves in the process. This has been fixed (`update-theme!`
now gates on `owns?`, matching `delete-theme!`; the HTTP handler returns 401
on failure; cross-tenant regression tests added at both the `api` and HTTP
layers).

The reason this matters beyond the one bug: the call-site inventory below was
produced by reading each file and asking "which idiom does this use," which
implicitly assumes every domain has *some* enforcement to categorize.
`update-theme!` had none, so it didn't show up as a mismatch — it just wasn't
looked at closely enough to notice the gap. A convergence plan whose entire
premise is "stop trusting call-site discipline" shouldn't itself rest on a
call-site-discipline audit. **Before executing the sequencing below, re-derive
the inventory by grepping every `rdbms/update!` / `rdbms/delete!` (and
`persistence/update-*!` / `persistence/delete-*!`) call site directly** and
confirm each one is either preceded by or fused with a scoping check, rather
than re-skimming per-file.

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
| `api/themes.clj` (`owns?`, delete only) | theme | C |
| `api/themes.clj` (`update-theme!`) | theme | **none — fixed, see below** |

Roughly 30+ call sites across 7 files. This table should be treated as a
starting point, not ground truth — see "Audit gap found during review" above.

## Identity threading (unaffected by this change)

`http_api/middleware.clj`'s `wrap-classify-identity` merges
`api/authentication.clj`'s `classify-identity` output into `:identity`:
`{:type :verified-user|:service-account|:unverified-user, :user-id ...,
:roles #{...}}`. All handlers read `(:user-id (:identity request))` — this
stays as-is; it's a separate concern (authentication/role ACL, handled by
`api/authorization.clj`'s `require-*-writer`/`reader`/`admin`) from resource
ownership (this plan).

## Authorization models in this codebase

This plan converges the idioms *within* one of several distinct
authorization models present in the codebase. Naming all of them explicitly
here, since the rest of this document only discusses them where relevant to
a specific gap, and a future reader shouldn't have to reconstruct the full
set from scattered mentions:

### Model 1 — Route-level RBAC (base layer, applies to every route)

- **Grants/restricts:** whether an identity can reach a given endpoint on a
  given site at all.
- **Mechanism:** `require-*-writer`/`-reader`/`-admin` (`api/authorization.clj`),
  role strings namespaced per site — `(str server-name ":writer")`, where
  `server-name` is the request's `Host` header.
- **Enforced:** buddy-auth middleware, before any handler runs.
- **Scope:** every route in `KALEIDOSCOPE-ACCESS-CONTROL-LIST`
  (`http_api/kaleidoscope.clj:31-64`) is gated by some tier of this model (or
  explicit `public-access`). It composes with each of Models 2-4 below,
  which answer "which specific rows/data can they touch once inside," not
  "can they get in."

### Model 2 — Per-row ownership (single owner) — this plan's subject

- **Grants/restricts:** which individual rows an identity that already
  passed Model 1 may read/mutate.
- **Mechanism:** `owner_id`/`user_id` equality between the requester and the
  row (plus `hostname` for themes — see gap below).
- **Enforced today:** 3+ inconsistent idioms (Patterns A/B/C in the Problem
  section), converging via this plan into `scoped-update!`/`scoped-delete!`/
  `get-owned`.
- **Scope:** `projects`, `tasks`, `workflows`, `score-definitions`,
  `agent-definitions`, `groups`, `themes`.

### Model 3 — Shared/site-wide write access, no per-row restriction

- **Grants/restricts:** nothing beyond Model 1 — any identity holding a
  site's `writer`/`admin` role may create, update, or delete *any* row of
  that type on that site.
- **Mechanism:** none; there is deliberately no ownership check layered on
  top of Model 1 for these resources.
- **Scope:** `articles`, `article-branches`, `article-versions` (writer-gated),
  `albums`, `portfolio-entries`, `portfolio-links`, `article-audiences`
  (admin-gated). `update-article!`'s unscoped-by-id update
  (`api/articles.clj:28-36`) is this model working as intended, not an
  unmigrated instance of Model 2's Pattern A.
- **Out of scope for this plan** — see Non-goals below.

### Model 4 — Group-based read visibility (audience gating)

- **Grants/restricts:** which *unpublished/restricted* articles a reader may
  see — a read-side concern only, with no write-side equivalent.
- **Mechanism:** `article-audiences` links an article to a `group`;
  `get-published-articles` (`api/articles.clj:172-202`) returns a restricted
  article only to requesters belonging to an audience group (or if the
  article is `public-visibility`), scoped per `hostname`.
- **Enforced:** in `api/articles.clj`, independent of Models 1-3.
- **Scope:** articles only — no other resource type uses group-membership
  visibility.
- **Out of scope for this plan** — see Non-goals below.

### Summary

| Model | Answers | Mechanism | Enforced where | Resources |
|---|---|---|---|---|
| 1. Route RBAC | Can this identity hit this endpoint, on this site? | Role string `<site>:writer/reader/admin` | Middleware, pre-handler | Every route |
| 2. Row ownership | Which specific rows can they touch? | `owner_id = requester` (+ `hostname` for themes) | API/persistence layer | projects, tasks, workflows, score-definitions, agent-definitions, groups, themes |
| 3. Shared site-wide access | (nothing beyond Model 1) | — | — | articles, branches, versions, albums, portfolio, article-audiences |
| 4. Group-based read visibility | Which articles can a reader see? | Group membership vs. article audience | `api/articles.clj`, read-side only | articles only |

Models 1, 3, and 4 are unaffected by this plan; they're named here so
"ownership consolidation" isn't mistaken for "the authorization system," and
so the `get-owned` primitive isn't assumed to eventually absorb articles or
audience-gated visibility.

### Essential vs. accidental complexity across these models

Using Hickey's vocabulary (*Simple Made Easy*): **simple** means unbraided —
one role, no interleaving with other concerns. **Complex** means *complected*
— two or more things folded together so neither can be reasoned about alone.
**Easy** is about familiarity/proximity, not structure, and following the
easy path is exactly how accidental complexity gets introduced. **Essential**
complexity is what the problem genuinely requires; everything else is
accidental — added by our constructs, not demanded by the domain.

**Essential complexity — inherent to this domain, not removable:**

- *Site × tier* (Model 1) and *row ownership* (Model 2) are genuinely
  independent facts. Passing one implies nothing about the other — Andrew
  being admin on one site and nothing on another is the normal shape of the
  domain, not an edge case.
- *Collective vs. owned* is a real domain distinction, not a missing
  feature. Articles are intentionally editable by any site writer (Model 3);
  that's a newsroom, not an unfinished filing cabinet.
- *Themes have an extra essential axis* (site) that projects/workflows/etc.
  don't. That's a genuine structural difference between resource types, not
  a variance to paper over with one identical signature.
- *Read authority and write authority are different questions* — Model 4's
  audience gating is a legitimate independent axis; who may see something
  doesn't follow from who may write it.
- *Whether admin overrides ownership* is a policy fact about the business,
  not a technical fact about SQL. It must be decided by someone; the only
  choice is whether it's decided consciously or falls out by accident.

**Accidental complexity — introduced by implementation choices, not the domain:**

- **Role and site complected into a string.** `(str server-name ":writer")`
  folds two independent facts (which site, which tier) into one opaque token
  compared by string equality — you can't ask "what sites does this identity
  have any access to" without parsing. Two facts welded into one string
  because concatenation was the easy move, not the simple one.
- **Ownership checks complected with control flow.** Pattern B's
  `(when (= (:user-id wf) user-id) wf)`, hand-copied per call site, braids
  "is this the owner" together with "what does this function do about it" —
  N copies of one decision instead of one decision applied N times. This is
  exactly what `get-owned` plus a data table (§2) decomplects: the *policy*
  becomes one function, the *variation* (table, owner-col, site-col)
  becomes inert data next to it.
- **"Looks correct" complected with "is correct."** `update-project!`/
  `delete-project!` take a `user-id` parameter and never use it — the
  signature implies scoping the SQL doesn't deliver. The most dangerous kind
  of accidental complexity, because it's invisible: it already looks like
  Model 2, so nothing prompts a re-check.
- **Naming history complected with meaning.** `user-id` vs `owner-id` for
  the identical concept across tables is a pure accident of two people at
  two moments choosing different words — it costs every future generic
  helper the need to know both spellings.
- **Response shape complected with which file wrote it.** 401 vs 404, `nil`
  vs `{:error :not-found}`, a logged warning vs silence — none of these
  differences carry domain meaning; they're signal about when the file was
  written, not about what happened.

**Resist "fixing" what's already correctly simple.** Models 1, 2, and 4 are
already decomplected from each other, and that's correct, not a gap: route
RBAC doesn't know about ownership, ownership doesn't know about group
membership. The *easy* move — one unified `Authorization` abstraction
handling all of it — would braid three independent questions together for
architectural tidiness, which is precisely how "easy" tempts you into new
accidental complexity. Likewise, Model 3 doesn't need a stub `owns?` that
always returns true for symmetry with Model 2 — the absence of a check *is*
the correct model for shared resources; building a parallel mechanism just
so every resource "looks the same" would complect shared-access semantics
with ownership machinery purely for uniformity.

## Interaction with role-based access control (per-site permissions)

Model 1 (route RBAC) and Model 2 (row ownership, this plan's subject) are
independent layers today: Model 1 gates whether a request can reach a
handler at all; Model 2 then further restricts *which rows* a request that
got past Model 1 may touch. This plan changes only Model 2. Two consequences
of that split need an explicit decision rather than being left implicit:

### Per-site permission differences are already handled correctly, and untouched by this plan

Because Model 1's role strings are namespaced per `server-name`, a user with
`andrewslai.com:admin` and no role at all on `sahiltalkingcents.com` is
rejected by `require-*-writer`/`-admin` the moment they hit a writer-gated
route via that Host header, before any handler or ownership check runs. This
plan doesn't need to (and shouldn't) reimplement any part of that — confirmed
by walking `require-*-writer` (`authorization.clj:41-56`) and
`KALEIDOSCOPE-ACCESS-CONTROL-LIST` (`http_api/kaleidoscope.clj:31-64`).

### Gap: no admin override in the ownership check

`require-*-admin` already lets an admin *reach* any writer-gated route, but
`owns?` / the proposed `scoped-update!` / `get-owned` check nothing but
`user-id = owner-id` equality — no role is consulted. Today, Pattern A's bug
(`update-project!`/`delete-project!` ignoring the `user-id` arg) is an
accidental backdoor that happens to let an admin (or anyone) bypass
ownership. Fixing that bug via `scoped-update!` is correct, but it also
removes the only existing path for legitimate admin moderation of another
user's row, with nothing put in its place. Decide one of:

- **No admin override** — an admin can reach the route but the SQL where
  clause still excludes them if they're not the owner; admin moderation of
  another user's project/theme/workflow requires direct DB access. Simplest,
  and arguably correct for "personal" resources.
- **Explicit admin override** — `scoped-update!`/`get-owned` take an
  `:allow-admin?` (or equivalent) flag so the where-map becomes
  `WHERE id=? AND (owner_id=? OR ?is-admin)`, and callers pass whether the
  resolved identity holds the site's admin role.

Either is defensible; the plan currently doesn't say which, so this should be
resolved before implementation rather than defaulting to "no override" by
omission.

### Gap: themes need a compound ownership key, not just `owner-id`

`themes` is the only in-scope table with a `hostname` column — set once at
creation from the request's `Host` header (`http_api/themes.clj:51`) — and
it's real per-site data (each site has its own themes). But `owns?` (and the
proposed generic `get-owned`) check only `owner_id = requester-id`; nothing
compares the row's `hostname` against the request's `server-name`.
Concretely: a user who holds writer roles on *multiple* sites can update a
theme tagged `hostname="andrewslai.com"` by sending the request through a
different site's Host header (say `sahiltalkingcents.com`) as long as they
own the row — RBAC passes (they do have `sahiltalkingcents.com:writer`) and
ownership passes (they do own the theme), even though neither role has
anything to do with the site the theme actually belongs to. The generic
resource-spec table (§2) needs to support a *compound* scoping key for
themes — `{:table :themes :owner-col :owner-id :site-col :hostname}` — with
the update/delete path binding `:site-col` to the request's `server-name`,
not just `:owner-col` to the requester's `user-id`. A single-column
`owner-col` spec, as currently drafted, would silently drop this and carry
the existing gap forward into the "converged" implementation.

## Non-goals: legacy CMS (articles, albums, portfolio)

Per `CLAUDE.md`, legacy CMS code isn't touched here unless explicitly the
task — but the reason goes beyond "it's old": articles run on Models 3 and 4
above, not an unmigrated instance of Model 2.

- `articles` has no `user_id`/`owner_id` column at all — just a free-text,
  non-authoritative `author` field. `update-article!`
  (`api/articles.clj:28-36`) does an unscoped `rdbms/update!` keyed only by
  `:id`, which looks like a Model 2 / Pattern A instance but isn't: there's
  no ownership invariant it's meant to enforce (Model 3). Folding articles
  into `get-owned`/`scoped-update!` would be a behavior change (restricting
  edits to the original author), not a consolidation, so it's excluded on
  principle, not just convenience.
- Model 4 (group-based audience visibility) is worth naming explicitly so a
  future reader doesn't assume it's covered by "the ownership model" once
  this plan lands — it answers a different question (read visibility) than
  Model 2 does (write ownership), and this plan doesn't touch it.
- `albums`/`portfolio`/`article-audiences` are gated `require-*-admin` only
  (`http_api/kaleidoscope.clj:63-64`) with no per-row ownership layer at all
  — access control there is entirely Model 1.

If per-article ownership (e.g. "only the author can edit their own post")
becomes a real requirement later, that's a deliberate product decision and a
separate migration (adding `user_id`, backfilling it, deciding what happens
to multi-author articles) — not something this plan's primitive should
absorb implicitly.

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

### 2. Uniform signature shape across every owned resource — and don't stop at convention

`get-X db id user-id`, `update-X! db id user-id updates`,
`delete-X! db id user-id` — applied to projects (fix the dead param),
workflows, score-definitions, groups, and themes. Groups/themes drop their
hand-rolled `owns?` + `get-owner` and call the same `scoped-update!`/
`scoped-delete!` as everyone else, passing `:owner-id` as their scoping key
(see open question below on whether to normalize the field name).

**This alone isn't the structural fix it's presented as.** It generalizes the
SQL primitive (good — the where-map is data, not control flow) but still
requires a human to hand-write three wrapper functions per domain that
*follow a convention*. That's the exact failure mode this plan exists to
eliminate, just moved up one layer — it's how `update-theme!` ended up with
no check at all (see audit-gap note above): someone wrote `create-theme!` and
`delete-theme!` carefully and didn't get to `update-theme!`. A convention
followed five times by hand is still five chances to skip it a sixth time.

Prefer finishing the generalization: a small data table describing each owned
resource —

```clojure
{:projects         {:table :projects          :owner-col :user-id}
 :workflows        {:table :workflows         :owner-col :user-id}
 :score-definitions {:table :score-definitions :owner-col :user-id}
 :groups           {:table :groups            :owner-col :owner-id}
 :themes           {:table :themes            :owner-col :owner-id}}
```

— plus *one* generic `get-owned`/`update-owned!`/`delete-owned!` that domains
call through (or that thin per-domain wrappers delegate to for a nicer call
site). Adding a resource type then means adding a table entry, not
remembering to reimplement three functions correctly. A missing entry is
visible in a list you can eyeball; a missing check buried in a 90-line file is
not — which is exactly how the themes gap survived.

### 3. API layer becomes a pass-through, not a checker

Fix the current WIP in `api/score_definitions.clj` and `api/workflows.clj`:
delete the `(when (= (:user-id defn) user-id) ...)` blocks. Once the
persistence call itself is scoped, "not owned" and "not found" collapse into
the same `nil`/no-op result — there's no comparison left to forget.

### 4. HTTP layer unchanged — with one caveat

Handlers keep threading `(:user-id (:identity request))` into each api call,
same as today — this layer was never the problem. But "unchanged" only holds
mechanically; the response *contract* it reflects is not actually uniform
today (`nil` vs `{:error :not-found}` vs a logged warning + `nil`; 404 for
workflows vs 401 for theme deletes). Converging the persistence/API layers
without deciding this will just freeze today's inconsistency in a shinier
implementation. Recommendation: collapse not-found and not-owned into the same
result (nil at the API layer, 404 at HTTP) everywhere, so a non-owner probing
IDs can't distinguish "doesn't exist" from "exists, not yours." Themes'
current use of 401 for this case is the outlier to fix, not the target to
converge on.

### 4b. Child/nested resources are a different shape — decide if they're in scope

Notes, skills, conversations, and section-questions (`api/projects.clj`) are
scoped by `project-id` alone: ownership is checked once transitively through
`get-project`, then the child row is fetched/mutated unscoped by
`project-id`. That's a defensible pattern on its own (nested identity, not
`id`+`user-id`), but it's a fourth shape not covered by the inventory above,
and it doesn't fit the `get-X db id user-id` signature the plan proposes —
these functions take `project-id` *and* a child id. Either scope them into
this plan explicitly (they go through the same persistence layer and are
worth the same "grep every mutation call site" pass from the audit-gap note),
or state explicitly that they're out of scope and why, rather than leaving
them unmentioned.

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

0. Re-verify the inventory: grep every `rdbms/update!` / `rdbms/delete!` /
   `persistence/update-*!` / `persistence/delete-*!` call site directly
   (don't re-skim per-file) and confirm each is scoped or covered by a
   preceding check. `update-theme!` (fixed 2026-07-03, see above) is the
   known instance this method already found; treat it as evidence the
   per-file read undercounts, not as the only one. Exclude `articles`/
   `article-branches`/`article-versions`/`portfolio-entries`/`portfolio-links`
   from this audit per the Non-goals section above — their unscoped updates
   are intentional shared-write access, not instances of Pattern A.
1. **New — decide the admin-override question** (§ "Gap: no admin override
   in the ownership check") before designing the primitive: does
   `scoped-update!`/`get-owned` need an `:allow-admin?` escape hatch, or is
   "owner-only, no exceptions" the intended final behavior? This has to be
   settled first because it determines the primitive's function signature
   and where-map shape in step 3 — retrofitting an admin bypass after
   domains have already migrated onto a fixed-shape primitive means
   revisiting every call site a second time.
2. Fix the in-flight WIP (`score_definitions.clj`, `workflows.clj`) to use
   the new scoped-mutation primitive instead of the fetch-then-compare
   pattern it currently adds.
3. Extend `persistence/rdbms.clj` with `scoped-update!`/`scoped-delete!`, and
   design the resource-spec data table (§2) alongside it rather than as a
   follow-up — the generic `get-owned`/`update-owned!`/`delete-owned!` should
   land in the same change as the primitive, not be retrofitted later. Build
   in, from the start: the admin-override mechanism decided in step 1, and a
   compound scoping key (`:owner-col` + optional `:site-col`) so `themes` can
   bind `:site-col` to the request's `server-name` (see "Gap: themes need a
   compound ownership key") rather than being force-fit into a single-column
   spec later.
4. Migrate `projects.clj` (fix the dead `user-id` param in
   `update-project!`/`delete-project!`).
5. Migrate `groups.clj`/`themes.clj` onto the same primitive, deleting their
   `owns?`/`get-owner` helpers. For `themes` specifically, confirm the
   update/delete call sites in `http_api/themes.clj` pass the request's
   `server-name` through as `:site-col`, not just the requester's `user-id`
   as `:owner-col` — this is the fix for the cross-site theme gap, not just a
   call-site rename.
6. Decide and apply the not-found/not-owned response contract (§4) so newly
   migrated domains don't inherit today's 401/404 split.
7. Decide whether child resources (§4b) are in scope for this pass; if yes,
   fold them into step 0's audit and migrate them onto `project-id`-scoped
   equivalents of the same primitive.
8. Add cross-tenant regression tests per resource, plus two tests specific to
   the gaps above:
   - **Cross-site theme test**: a user with writer roles on two different
     sites cannot update/delete a theme belonging to site A by sending the
     request through site B's Host header, even though they own the row.
   - **Admin-override test**: whichever behavior was chosen in step 1 —
     either an admin's request against another user's row is confirmed to
     still fail (no-override case), or an admin's request is confirmed to
     succeed while a same-role non-admin's request against the same row
     fails (override case).
