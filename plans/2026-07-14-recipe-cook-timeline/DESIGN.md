# Recipe Cook Timeline — Design

A visual, Gantt-style **cook timeline** generated from a recipe: each part of the
recipe becomes a block on a real-minute axis, laid into parallel lanes, so a
visual learner can see *when* to do each step — and, crucially, when to start a
hands-off step (marinate, simmer, rest) so it's ready in time.

Prototype (look/feel, sample data): the approved Prism artifact —
`Miso-Glazed Salmon Rice Bowls`. This doc specifies how to *generate* the
timeline behind that view and where it lives.

Related: `plans/2026-07-10-recipes-feature/PLAN.md`,
`plans/2026-07-11-recipe-sections/DESIGN.md`.

---

## Terminology (the one thing to get straight)

`content.sections` is overloaded, so this doc uses precise names:

| Term | Definition | In the recipe data |
|---|---|---|
| **Component** | A dish part pairing ingredients with steps (e.g. "Salmon", "Rice") | `content.sections[i]` |
| **Lane** | One horizontal row in the Gantt | = one **component** |
| **Phase** | The **atomic unit of time** — a contiguous group of a component's steps with one duration (e.g. "Marinate", "Sear & glaze") | derived; not stored in `content` |

A component (lane) holds **one or more phases**. The phase is the atomic block;
"treat each section like an atomic unit of time" is realized at the *phase*
level. This matches the approved prototype exactly.

---

## Decisions (settled)

- **Time axis:** real minutes. Each phase has an estimated duration; bars are
  scaled to a clock axis. The UI back-plans clock times from a serve time.
- **Lanes:** grouped by **component/dish**, as in the prototype.
- **Scheduling source:** the LLM segments components into phases and estimates
  duration, kind (active/passive), and dependencies. A **deterministic packer**
  (no LLM) assigns start times.
- **Durations:** shown as a single estimate; the user can **nudge** any phase's
  duration and **re-pack** (cheap, no LLM).
- **Regeneration:** the LLM re-estimates **only when a component's steps
  changed** (per-component `steps-hash` fingerprint). Unchanged components are
  served from the durable store untouched. Nudges on unchanged components
  survive; nudges on a changed component are dropped (its estimate is now stale).
- **When it computes:** the frontend calls the timeline endpoint **after a
  successful save**. The LLM call stays out of the recipe write path / DB
  transaction — a save never fails because the LLM did.
- **Storage:** a **durable materialized derived value** — a new nullable
  `timeline` JSONB column on `recipes` (not embedded in `content`, not versioned
  separately today, but forward-compatible with future content versioning).

### Durable vs recomputable (they're orthogonal)

- **Recomputable** — losing the derived cache is not data loss; it rebuilds
  deterministically from content + overrides. It is *not* the source of truth.
- **Durable** — we persist it in Postgres anyway, *because* regeneration is
  expensive (LLM cost + latency). Reads and re-renders never trigger the LLM.

| Data | Stored | Rebuild cost | Source of truth |
|---|---|---|---|
| Authored overrides + serve pref | durable | — (human input) | **yes** |
| Phase segmentation + duration estimates | durable | expensive (LLM) | no — rebuildable |
| Packed start times | durable | cheap (pure packer) | no — rebuildable |

Only the **authored overrides** must never be silently dropped. Everything else
is persisted purely as an optimization.

### Load-bearing invariant: overrides annotate phases

An override is **not** a fact about content — it's an *annotation on a derived
phase*. Its key (`"{component}/{label}"`) names a phase the segmenter invented;
it can't even be expressed without the timeline to point at. Override and phase
therefore share one identity and one lifecycle, which is why they live together
in the blob rather than being split into an "authored" store — they are not
independent things.

What keeps an annotation valid across regenerations is **phase-identity
stability**, and that is exactly what the trust boundary (below) guarantees: for
an unchanged component the cached phases are kept verbatim (the LLM's
re-segmentation is discarded), so the phase key an override points at does not
move. When a component's steps *do* change, its phases are legitimately new and
its overrides are legitimately dropped — the annotation's target is gone. This
coupling is cohesion, not complecting; the trust boundary is what makes it safe.

---

## Data model

### Migration

`resources/migrations/NNNNNNNNNNNNNN-add-recipe-timeline.up.sql`:

```sql
ALTER TABLE recipes ADD COLUMN timeline JSONB;   -- nullable; NULL = not yet generated
```

`.down.sql`: `ALTER TABLE recipes DROP COLUMN timeline;`

No new table. The column rides along on every existing `GET`.

### `timeline` blob shape

```clojure
{:version          1                 ; blob-shape version (migrate forward if it changes)
 :generator-version 1                ; bump to force a lazy regen when the prompt improves
                                     ; (mirrors the scraper's :pipeline-version)
 :generated-at     "2026-07-14T…Z"
 :total-minutes    50

 ;; AUTHORED — the only non-recomputable layer. Lifts into a version envelope later.
 :overrides [{:phase "Salmon/Sear & glaze" :minutes 12}]

 ;; DERIVED CACHE — disposable, content-addressed per component.
 :components
 [{:name       "Salmon"
   :steps-hash "sha256:…"            ; fingerprint of THIS component's steps
   :phases
   [{:id       "Salmon/Marinate"     ; stable key = "{component}/{label}"
     :label    "Marinate"
     :kind     :passive              ; :active | :passive
     :estimate 24                    ; LLM minutes (authored overrides applied at pack time)
     :steps    [0 1 2]               ; indices into the component's steps this phase covers
     :deps     []                    ; list of phase :id — resolved by (component,label)
     :start    0}                    ; packer output (minutes from t0)
    …]}
  …]}
```

**Keys are `"{component}/{label}"`, and deps reference other phases by that key.**
This is what makes per-component regeneration + override preservation robust:
identity survives across the cache/LLM boundary without opaque UUIDs. Labels are
unique within a component (LLM-instructed + validated).

### Malli schemas

Add `Timeline`, `TimelineComponent`, `Phase`, `Override` to
`models/recipes.clj`; validate at the HTTP boundary (endpoint input) and on
LLM output before persisting. `GetRecipeResponse` gains an optional
`:timeline` (nullable).

---

## Generation & merge algorithm

`api/recipe_timeline.clj` (new domain namespace). Given a recipe + its stored
timeline (possibly nil):

1. **Fingerprint.** Compute `steps-hash` for each current component. Compare to
   the stored blob → the **changed set** `C`.
2. **Short-circuit.** If `C` is empty *and* `generator-version` is current →
   return the stored blob unchanged (no LLM, no write).
3. **Segment (LLM, one call, full-recipe context).** Send the whole recipe so
   the model can infer **cross-component dependencies**, but mark which
   components are `CHANGED` and include the cached phases for the unchanged ones.
   The model returns, for every component, its phases `{label, kind, steps,
   duration}` and phase-level `deps` as `(component, label)` references.
4. **Assemble the final phase set** — the trust boundary:
   - **Unchanged component** → keep its **cached phases** (authoritative; ignore
     what the LLM returned for it). Guarantees stability of ids, labels, and
     override alignment.
   - **Changed component** → use the LLM's fresh phases; its prior overrides are
     dropped.
5. **Resolve deps.** Map each `(component, label)` dep to a final phase id; drop
   any that don't resolve (packer tolerates missing deps).
6. **Carry overrides.** Keep override entries whose component is unchanged; drop
   those whose component changed.
7. **Pack** (below) and persist the new blob.

Only steps 3 costs an LLM call, and only when `C` is non-empty.

### The packer (pure, deterministic)

Greedy list-scheduler over the phases. Effective duration =
`override.minutes ?? phase.estimate`.

- Topologically order phases by `deps` (authored order breaks ties).
- Maintain `cook-free-at` (when the single cook — two hands — is next free).
- For each phase, `ready = max(end of each dep, 0)`:
  - **passive** → `start = ready` (unattended; may overlap anything).
  - **active** → `start = max(ready, cook-free-at)`; then
    `cook-free-at = start + duration`.
- `total-minutes = max(end of all phases)`.

This is a heuristic (precedence-constrained single-machine scheduling is NP-hard
in general), but it's exact-enough and instant for the ~5–15 phases a recipe
has. Passive phases float as early as their deps allow → the "start the marinade
now" insight. Cycle guard: if deps form a cycle, drop the back-edge and log.

### Nudge → re-pack

A duration nudge sets/updates an entry in `:overrides`, then re-runs **only the
packer** (no LLM) and persists. The client can pack locally for instant feedback
and `PUT` the override set to persist.

---

## The LLM generator (pluggable, mock + real)

Mirrors the existing scorer / workflow-executor pattern (protocol + `mock` for
tests/local + `llm` for production; selected in `init/env.clj`). A new
`:timeline-generator` component.

- **`mock`** (default when `ANTHROPIC_API_KEY` absent; used in all tests):
  deterministic segmentation — one phase per component by default, split when a
  step matches passive cues; `duration = base + k·(step count)`; `kind` by
  keyword (`marinate|rise|proof|chill|rest|refrigerate|soak|bake|roast|simmer|
  freeze|marinade` → `:passive`, else `:active`); deps = linear within a
  component + the **last component in authored order** depends on the last phase
  of every other component (stands in for a plate/assembly step). Deterministic,
  so tests can assert exact schedules.
- **`llm`**: reuses the scraper's Anthropic setup (`:api-key` from the
  workflow-executor component; same model the recipe scraper uses). One
  non-streaming JSON call. Output is Malli-validated before it's trusted; a
  malformed response is a generation failure (see error handling).

---

## HTTP surface

Extends `http_api/recipes.clj`. Timeline **viewing** is part of viewing the
recipe (already in the `GET` payload, gated by the recipe's own visibility).
Timeline **authoring** is writer-only (like `/lineage`).

| Method + route | Auth | LLM? | Purpose |
|---|---|---|---|
| `GET /recipes/:recipe-url` | recipe visibility | no | returns recipe incl. stored `:timeline` |
| `POST /recipes/:recipe-url/timeline` | writer-only | yes (if steps changed) | regenerate from current content; persist; return timeline. **Frontend calls this after a successful save.** |
| `PUT /recipes/:recipe-url/timeline` | writer-only | no | apply `{:overrides […]}`, re-pack, persist, return timeline |

- `POST` runs the generation/merge algorithm; the LLM call is **outside** any DB
  transaction and independent of the recipe write.
- Non-writers hit `POST`/`PUT` → 404 (no leak), consistent with `/lineage`.

### Error handling

- LLM failure / malformed output on `POST` → `502` (or `503` on rate-limit),
  with a `{:reason …}` body the client can act on. The **recipe stays saved**;
  the stored `:timeline` is untouched (previous value or `NULL`). The client
  shows "timeline unavailable — retry".
- `PUT` never calls the LLM, so it only fails on validation (`400`) or a missing
  recipe (`404`).

---

## Layering (3-layer separation preserved)

```
persistence/  migration adds recipes.timeline (JSONB). rdbms JSON helpers already handle it.
api/          recipe_timeline.clj — segment (via injected :timeline-generator), pure pack,
              fingerprint diff, merge, override application. No HTTP, no direct SQL beyond rdbms.
              recipes.clj — read/write the timeline column alongside the recipe.
http_api/     recipes.clj — the two routes above; auth; Malli validation; error mapping.
init/env.clj  wire :timeline-generator (mock|llm) like the scorer/workflow-executor.
```

No handler recomputes on read; the column is authoritative.

---

## Forward compatibility with recipe versioning

Recipes are **not** versioned today (`content` is mutated in place;
`original_content` is only the immutable scrape). This design does **not** build
versioning, but slots into it cleanly if it lands:

- The timeline is a **pure function of content + authored overrides**, so a
  versioned content snapshot can always reconstruct its timeline — no
  independent timeline history needed.
- **Authored overrides** are already isolated (`:overrides`), so they lift into a
  future version envelope as authored data (like `content`); the derived
  `:components` cache stays disposable.
- Keeping the blob a **sibling column** (not inside `content`) means it never
  pollutes the authored source or the immutable scrape.

If/when a `recipe_content_versions` model is introduced, the timeline moves from
"column on the mutable recipe" to "derived per version" with the overrides
riding in the version — no change to the generation algorithm or packer.

---

## Testing

Framework: Kaocha + matcher-combinators, embedded-postgres (recipes already run
on it for the JSONB queries).

- **Packer (pure) — unit, heavy coverage:** dependency ordering; active phases
  never overlap (single-cook); passive phases float earliest; override applied
  over estimate; `total-minutes` = makespan; dangling-dep tolerance; cycle
  guard.
- **Fingerprint / merge — unit:** editing one component's steps marks only that
  component changed; unchanged components keep cached phases + overrides; changed
  component drops its overrides; `generator-version` bump forces regen.
- **Mock generator — unit:** deterministic phases; passive-keyword classification.
- **HTTP — e2e (mock generator, no Anthropic):**
  - `POST /timeline` on a fresh recipe returns a well-formed schedule; column
    persisted; `GET` returns it.
  - Edit one component's steps → `POST` regenerates only that component
    (assert via mock call scope / unchanged components' `steps-hash`).
  - `PUT` overrides → re-pack shifts starts, no generator call; persisted.
  - Non-writer `POST`/`PUT` → 404. Generator failure → 502, recipe still saved.

Per project rule: **every feature ships with automated tests** — packer + merge
get thorough unit coverage; the endpoints get e2e coverage.

---

## Out of scope (v1)

- Building recipe content versioning (forward-compatible only).
- Equipment/resource lanes (stove/oven contention) — lanes are dish/component.
- Duration *ranges* — a single estimate, user-nudgeable.
- Server-side post-save trigger — the frontend calls `POST /timeline` after save.
- The frontend Gantt component itself (kaleidoscope-ui repo) — this spec is the
  backend + data contract; the prototype is the visual reference.
