# Engineering Reviewer Code Context

## Concept

The Engineering Reviewer currently evaluates a project based on its written brief
alone. When the project describes work on an existing codebase, a brief-only review
is blind to the actual architecture, technology choices, and implementation
constraints that matter most.

This feature gives the Engineering Reviewer access to the local filesystem. When a
codebase can be identified from the project description, the reviewer reads relevant
files automatically and incorporates them into its evaluation. When identification is
ambiguous, the reviewer asks the user to confirm before proceeding.

The user's job is to write their project description naturally. If they say "in the
kaleidoscope project, please implement…" the system figures out the path. They do not
need to configure anything per project unless they want to.

---

## What the user experiences

**Normal case — codebase identified automatically**: The Engineering Review card
shows a subtle note: *"Reviewed with context from /Users/alai/code/kaleidoscope"*.
The review produces findings specific to the actual code, not just the description.

**Ambiguous case — system asks**: The Engineering Review card shows a question:
*"I found a few possible codebases. Which one should I review?"* with a short list
of candidates and a text field for a custom path. The user picks one and the review
proceeds.

**No match found**: Same as ambiguous, but the list of candidates may be empty.
The user types a path directly.

**Manual override**: A path set explicitly on the project is always used without
any matching. Useful when the project title is generic or the codebase lives
somewhere unexpected.

---

## Workspace roots

The system needs to know where to look. The user registers one or more
**workspace roots** — directories where their codebases live (e.g.
`/Users/alai/code`). These are registered once in settings and apply to all
projects.

When the Engineering Reviewer runs, the system lists the immediate subdirectories
of every registered root to build a candidate list. It does not recurse further.

Workspace roots are per-user and stored in the database.

---

## Matching algorithm

Matching is text-based and deterministic. No LLM is used for matching.

```
normalize(s) → lowercase, replace [-_/. ] with spaces, trim

candidates = immediate subdirectories of all workspace roots

for each candidate directory:
  n = normalize(candidate.basename)
  t = normalize(project.title)
  d = normalize(project.description)

  score:
    1.0  exact:          n == t
    0.85 title contains: n is a substring of t
    0.80 name contains:  t is a substring of n
    0.65 word overlap:   any word in t appears in n (and word length > 3)
    0.60 desc mention:   n appears as a substring of d
    0.0  otherwise

confidence threshold for auto-use: 0.80
```

If exactly one candidate scores ≥ 0.80, it is used automatically and the selection
is noted in the step output.

If multiple candidates score ≥ 0.80, or the top score is between 0.50 and 0.79,
the step pauses and presents the ranked candidates to the user.

If no candidate scores ≥ 0.50 and no workspace roots are configured, the step
pauses and asks the user to provide a path directly.

### Examples

| Project title | Candidates | Selected |
|---|---|---|
| `kaleidoscope` | `kaleidoscope`, `kaleidoscope-ui`, `other` | `kaleidoscope` (1.0, exact) |
| `kaleidoscope ui` | `kaleidoscope`, `kaleidoscope-ui` | `kaleidoscope-ui` (0.85) |
| `add feature to server` | `server`, `my-server` | pause — two candidates score ≥ 0.80 |
| `migrate billing data` | `billing`, `billing-service` | pause — both score ≥ 0.80 |
| `new project` | `kaleidoscope`, `blog` | pause — no match above threshold |

---

## File reading

Once a path is resolved, the system reads files from it to build a code context
block injected into the Engineering Reviewer's scoring prompt.

**What gets read:**

- For a path that is a **regular file**: read it directly.
- For a path that is a **directory**: recursively collect all regular files,
  skipping noise directories (see skip-list below). Files are sorted by priority
  tier before reading so the 50k total cap is spent on high-signal files first.
  When the cap is reached, remaining collected files are recorded as
  "cap reached — not read" in the transparency block.

**Noise directory skip-list (hardcoded constant):**

`node_modules`, `.git`, `target`, `dist`, `build`, `out`, `__pycache__`,
`.gradle`, `.next`, `coverage`, `vendor`, `.cache`, `.idea`, `.vscode`,
`tmp`, `log`, `logs`

Hardcoded rather than user-configurable. Users who need finer control can
specify explicit file or subdirectory paths in `local_paths`.

**Priority tiers (applied before reading; lower tier = read first):**

| Tier | Files |
|---|---|
| 1 | Names matching `main.*`, `core.*`, `schema.*`, `routes.*`, `models.*`, `app.*`, `server.*`, `handler.*`, `index.*`; also `README.md`, `ARCHITECTURE.md`, `DESIGN.md`, `ADR*.md`, `docs/*.md` |
| 2 | Source-extension files inside `src/`, `lib/`, `app/`, `pkg/`, `api/`, `handlers/`, `internal/`, `cmd/` — shallower first |
| 3 | Source-extension files anywhere else in the tree |
| 4 | Project metadata: `deps.edn`, `package.json`, `Cargo.toml`, `go.mod`, `pyproject.toml`, `mix.exs`, `Dockerfile`, `docker-compose.*`, `*.yml`, `*.yaml`, `Makefile` |
| 5 | Everything else not already skipped |

Source extensions: `.clj`, `.cljc`, `.cljs`, `.py`, `.go`, `.ts`, `.tsx`,
`.js`, `.jsx`, `.rb`, `.rs`, `.java`, `.kt`, `.ex`, `.exs`, `.hs`, `.scala`,
`.cs`, `.fs`, `.ml`

Within each tier: shallower paths first, then alphabetical.

**What gets skipped regardless of tier:**

- Noise directories (skip-list above — entire subtree is excluded)
- Binary extensions: `.jar`, `.class`, `.png`, `.jpg`, `.gif`, `.svg`,
  `.ico`, `.zip`, `.gz`, `.tar`, `.exe`, `.so`, `.dylib`, `.woff`, `.woff2`,
  `.ttf`, `.eot`
- Lock files: `*.lock`, `*-lock.json` (e.g. `package-lock.json`, `yarn.lock`,
  `Gemfile.lock`) — machine-generated noise
- Files whose resolved canonical path does not start with the declared root
  path (prevents symlink traversal)
- Files that would push total content past the byte cap (recorded separately
  as "cap reached — not read", distinct from skipped)

**Caps:**

- 10,000 characters per file (files are truncated at this limit with a note)
- 50,000 characters total across all files (reading stops when this is reached)

Skipped and truncated paths are listed in a header within the context block.

**Format injected into the scoring prompt:**

```
<code_context>
Root: /Users/alai/code/kaleidoscope
Strategy: recursive (skipped dirs: node_modules, target)
Files read (14): src/kaleidoscope/main.clj, src/kaleidoscope/http_api/projects.clj, README.md, ...
Truncated at 10k: src/kaleidoscope/workflows/llm_executor.clj
Cap reached — not read (23 files): src/kaleidoscope/persistence/rdbms.clj, ...
Skipped binary (4): resources/logo.png, ...
Skipped lock (2): package-lock.json, yarn.lock

--- src/kaleidoscope/main.clj ---
<contents>

--- src/kaleidoscope/http_api/projects.clj ---
<contents>

--- README.md ---
<contents>
</code_context>
```

The distinction between "cap reached — not read" and "skipped" is preserved in
the header. The reviewer uses this to weight its findings — if a file central to
the brief appears in the "not read" list, the reviewer knows its assessment of
that area is based on the brief alone.

---

## The pause flow

When the step cannot auto-select a path, it transitions to `awaiting_input` without
mutating `output_kind`. The step's kind (`output_kind = "score"`) is immutable — it
describes what the step **is**, not what state it is currently in. Encoding transient
execution state in a column that identifies step kind creates an ambiguity that cannot
be resolved without knowing when the row was read.

Instead, the executor writes a `pending_inputs` JSON object onto the step run:

```json
{
  "kind": "code_context_path",
  "question": "Which codebase should I review?",
  "candidates": [
    { "path": "/Users/alai/code/kaleidoscope", "score": 0.85, "reason": "title contains directory name" },
    { "path": "/Users/alai/code/kaleidoscope-ui", "score": 0.60, "reason": "mentioned in description" }
  ]
}
```

The step's `status` is set to `"awaiting_input"`. `output_kind` remains `"score"`
throughout the entire pause/resume cycle.

The frontend reads `pending_inputs` to determine what question to render. The `kind`
field selects the appropriate input component. Any future step kind that needs user
input before execution declares its requirement the same way — no new special cases
in `respond-to-step!`.

When the user submits their answer via the existing `respond-to-step!` endpoint:

1. The selected or typed path is saved to `projects.local_paths` as `[path]`.
2. The step run's `pending_inputs` is cleared and `status` reset to `"pending"`.
3. The workflow run status is set back to `"in_progress"`.
4. `advance-step!` is called in the background — the score step re-runs, now with
   the path available, and finds it via `local-paths` override (skipping matching).

### Parallel step coordination

The Engineering Review step runs as a `parallel` step alongside PM Review. If it
pauses, its future resolves (the step returned) but the Judge must not run.

The correct fix is at the execution model level, not a compensating guard.
The `fan_in` step waits for all preceding parallel steps to reach a **terminal**
state. `awaiting_input` is not terminal — the step is paused, not done. The
`terminal?` predicate must be:

```clojure
(def terminal-statuses #{"completed" "failed" "timed_out"})
;; "awaiting_input" is deliberately excluded
(defn terminal? [step-run] (terminal-statuses (:status step-run)))
```

With this definition, `run-parallel-steps!` naturally blocks until all parallel
steps reach a terminal state — which a paused step never will until the user
answers. The fan-in is then never reached while a step is waiting for input.
No special guard needed.

```
run-parallel-steps! waits for all parallel steps to be terminal?
  awaiting_input steps are not terminal → futures do not resolve as "done"
  → fan_in is never triggered while any step is paused
```

The workflow run's own `awaiting_input` status is set by `resolve-requirements!`
when it pauses a step — the outer run status mirrors the step status.

---

## Manual override

A `local_paths` column on `projects` stores an explicit list of paths (files or
directories). When non-empty, `local_paths` is used directly and no matching is
performed. This is set via `PUT /projects/:id/local-paths`.

The manual override persists across workflow runs. It is cleared by submitting an
empty array.

---

## Data model

### New table: `user_workspace_roots`

```sql
CREATE TABLE user_workspace_roots (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    TEXT NOT NULL,
  path       TEXT NOT NULL,
  label      TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (user_id, path)
);
```

### Modified table: `projects`

```sql
ALTER TABLE projects
  ADD COLUMN local_paths JSONB NOT NULL DEFAULT '[]';
```

Stores a JSON array of path strings. Empty array means "use auto-detection".

### Modified table: `workflow_steps`

```sql
ALTER TABLE workflow_steps
  ADD COLUMN requires JSONB NOT NULL DEFAULT '[]';
```

Stores the step's declared requirements as a JSON array. Example for the
Engineering Review step:

```json
[{"kind": "code_context_path"}]
```

An empty array means the step has no pre-execution requirements. The executor
reads this column to drive generic requirement resolution — agent type is not
inspected.

### Modified table: `project_workflow_step_runs`

```sql
ALTER TABLE project_workflow_step_runs
  ADD COLUMN pending_inputs JSONB;
```

`pending_inputs` carries what a step is waiting for when `status = 'awaiting_input'`.
It is `NULL` when the step is not paused. `output_kind` is never mutated — it
identifies the step's kind and remains constant for the lifetime of the step run.

---

## Code structure

### New namespace: `kaleidoscope.utils.local-files`

Pure filesystem I/O. No database dependencies, no knowledge of projects or matching.

```
NOISE-DIRS
  #{node_modules .git target dist build out __pycache__ .gradle .next
    coverage vendor .cache .idea .vscode tmp log logs}
  Hardcoded constant. Entire subtrees rooted at these directory names are skipped.

collect-recursive
  [^java.io.File root]
  → [^java.io.File ...]

  Recursively walks root, skipping any directory whose name is in NOISE-DIRS.
  Returns a flat list of regular files. Caps at 2000 files to bound walk time
  on large trees; logs a warning when truncated.

path-info
  [^java.io.File f ^java.io.File root]
  → {:name "core.clj" :depth 3 :parent-dir "src"}

  Extracts the data a tier scorer needs from a File. Called at the I/O boundary.
  The only function in this namespace that takes two File arguments.

tier-score
  [{:keys [name depth parent-dir]}]
  → int (1–5)

  Pure function. Takes a path-info map, not a File object. See File reading section
  for tier definitions. Fully testable with plain maps — no filesystem required.

prioritize-files
  [files ^java.io.File root]
  → [^java.io.File ...]

  Maps each file through path-info, sorts by (tier-score, depth, name), returns
  sorted list of File objects for the subsequent read pass.

read-local-paths
  [paths & {:keys [max-total max-per-file]
            :or   {max-total 50000 max-per-file 10000}}]
  → {:files    [{:path "..." :content "..." :truncated false} ...]
     :not-read ["path/to/uncapped-file.clj" ...]
     :skipped  [{:path "..." :reason :binary|:lock|:traversal} ...]}

  Accepts a mix of file and directory paths.
  For files: reads directly.
  For directories: calls collect-recursive → prioritize-files → reads in tier order.
  Returns structured file records. Does NOT concatenate content — that is
  formatting, not reading, and belongs in format-code-context.

format-code-context
  [{:keys [root files not-read skipped strategy]}]
  → string

  Assembles the <code_context> string from file records. Concatenation of file
  contents happens here, not in read-local-paths. Keeping the two separate means
  the file records can be used for other purposes (e.g. a UI file browser showing
  what was read) without re-parsing a pre-assembled string.

binary-extension?
  [^java.io.File f]
  → boolean

lock-file?
  [^java.io.File f]
  → boolean
  Matches *.lock and *-lock.json patterns.

safe-slurp
  [^java.io.File f max-chars]
  → {:ok "..." :truncated false}  |  {:error "..."}

confined-path?
  [^java.io.File root ^java.io.File f]
  → boolean
  Checks canonical paths to prevent symlink traversal.
```

### New namespace: `kaleidoscope.utils.path-matching`

Pure string matching. No I/O, no database. Takes strings in, returns scores out.

```
normalize
  [s]
  → string
  Lowercase, replace [-_/. ] with spaces, trim.

score-candidate
  [candidate-basename project-title project-description]
  → {:score 0.0-1.0 :reason "..."}

  Computes a match score between a candidate directory name and project metadata.

find-best-match
  [candidates project]
  → {:path "..." :score 0.0-1.0 :reason "..."}  |  nil

  Returns the best candidate above the auto-use threshold, or nil if none clears it.
  Returned map includes the full ranked list for use in pause output.

scan-workspace-roots
  [roots]
  → [{:path "..." :basename "..."}]

  Lists immediate subdirectories of all provided roots. Skips roots that don't
  exist or aren't directories. Calls into local-files for directory listing only.
```

### New namespace: `kaleidoscope.persistence.workspace-roots`

```
get-workspace-roots   [db user-id]  → [{:id :user-id :path :label :created-at}]
add-workspace-root!   [db user-id path label]  → created row
delete-workspace-root! [db workspace-root-id user-id]  → deleted row | nil
```

### Modified: `kaleidoscope.scoring.agents`

```
build-scoring-user-prompt-with-code
  [project score-definition code-context]
  → string

  Calls build-scoring-user-prompt then appends the <code_context> block.
```

The `engineering-lead-system-prompt` gains one paragraph instructing it to use
code context when present and to note when scoring is based on the brief alone.

### Modified: `kaleidoscope.workflows.llm-executor`

Step requirements are declared as data on the step definition (see Data model).
The executor resolves them generically before dispatch — no agent-type branches.

#### Pure requirement resolution (no side effects)

```
resolve-code-context-path
  [project workspace-roots]
  → {:path "..."}
  | {:needs-input true :candidates [...]}

  Pure function. No database, no state.
  1. If local-paths is non-empty → return {:path (first local-paths)}
  2. Else if workspace-roots exist → run path-matching/find-best-match
       a. confidence ≥ 0.80 → return {:path best-match-path}
       b. otherwise         → return {:needs-input true :candidates ranked-list}
  3. Else (no workspace roots) → return {:needs-input true :candidates []}
```

The decision and the effect are separate. The caller decides what to do with the result.

#### Generic requirement resolution in the executor

```
resolve-requirements!
  [db step-def step-run project]
  → {:resolved {:code_context_path {:path "..."}, ...}}
  | :needs-user-input   (side effect: pending_inputs written, status set)

  For each requirement in step-def.requires:
    call the resolver registered for requirement.kind
    if result is :needs-input →
      write pending_inputs (kind + candidates) onto step-run
      set step-run status to awaiting_input
      return :needs-user-input (executor returns without dispatching)
    else →
      accumulate resolved value

execute-step!
  1. result = resolve-requirements!(db, step-def, step-run, project)
  2. if result = :needs-user-input → return (step is paused; output_kind unchanged)
  3. dispatch execute-step-by-kind! with step-def, step-run, resolved inputs

execute-step-by-kind! :score
  Receives resolved inputs (may include :code_context_path or nothing).
  If :code_context_path is present → read-local-paths, format-code-context,
                                      score with context
  Otherwise → score without context
  No agent-type branches. The step definition's requires field controls whether
  context is resolved, not a hardcoded check on agent type.
```

#### Requirement resolvers registry

```clojure
(def requirement-resolvers
  {"code_context_path" resolve-code-context-path})
```

Adding a new requirement kind — for any future step that needs user-provided data
before execution — means registering a resolver. No new branches in the executor.

### Modified: `kaleidoscope.api.workflows`

New branch in `respond-to-step!` for steps where `pending_inputs.kind = "code_context_path"`:

```
1. Parse answers as paths (trim whitespace, drop blanks)
2. update-project! with {:local-paths (vec answers)}
3. update-step-run! with {:status "pending" :pending-inputs nil}
   (output_kind is NOT touched — it remains "score")
4. update-workflow-run! with {:status "in_progress"}
5. fire advance-step! in background future
6. return get-workflow-run
```

New guard in `run-loop-workflow!` after `run-parallel-steps!`:

```clojure
(when (some #(= "awaiting_input" (:status %))
            (persistence/get-step-runs-by-round-and-mode db run-id round-id "parallel" nil))
  (persistence/update-workflow-run! db run-id {:status "awaiting_input"})
  (return))
```

### New HTTP routes: `kaleidoscope.http-api.workspace-roots`

```
GET    /workspace-roots          → list roots for the authenticated user
POST   /workspace-roots          → add a root {path, label?}
DELETE /workspace-roots/:id      → remove a root
```

### Modified HTTP routes: `kaleidoscope.http-api.projects`

```
PUT /projects/:id/local-paths    → body {local_paths: [...]}  → updated project
```

---

## Frontend changes

### Workspace roots settings

A new section in the application settings (or user profile page) for managing
workspace roots. Shows registered roots as a list with remove buttons. Add button
opens a dialog with a text field for the path and optional label.

### `code_context_path` input renderer

When a step has `status === "awaiting_input"` and `pending_inputs.kind === "code_context_path"`,
the step renders differently from a generic awaiting-input step. `output_kind` is
still `"score"` — the renderer switches on `pending_inputs.kind`, not on `output_kind`.

`pending_inputs` provides the question and the ranked candidates list.

```
┌─────────────────────────────────────────────────────────┐
│ 🦉 Engineering Review                  Needs your input │
├─────────────────────────────────────────────────────────┤
│ Which codebase should I review?                         │
│                                                         │
│ ● /Users/alai/code/kaleidoscope         ← title match   │
│ ○ /Users/alai/code/kaleidoscope-ui      ← in desc       │
│ ○ Enter a path manually…                                │
│   [_______________________________]                     │
│                                                         │
│ [Use this path]   [Skip code review]                    │
│                                                         │
│ ☐ Remember this path for this project                   │
└─────────────────────────────────────────────────────────┘
```

"Remember this path for this project" sets `local_paths` on the project via the
`PUT /projects/:id/local-paths` endpoint alongside the `/respond` call.

If the step's stored output is empty or cannot be parsed, fall back to a plain
text field (same behaviour as today's `awaiting_input`).

### Score card attribution

When a score step completes with code context, the Engineering Review card shows
a subtle note below the header:

> *Reviewed with /Users/alai/code/kaleidoscope*

This is sourced from a `context_path` field in the step run's `output` JSON.
The score step output shape must be defined as a Malli schema — the `output`
column is `TEXT` and without a schema the `context_path` field is an implicit
contract invisible to the type system:

```clojure
(def ScoreStepOutput
  [:map
   [:score-run-id :uuid]
   [:context-path {:optional true} :string]])
```

The frontend reads `context_path` from the parsed output. The Malli schema is
the contract between the executor (writer) and the frontend (reader). No DB
schema change required — the value is stored in the existing `output TEXT` column.

---

## Implementation order

**Phase 1 — Data layer**

1. Migration: `user_workspace_roots` table + `local_paths` column on `projects` +
   `requires JSONB` column on `workflow_steps` + `pending_inputs JSONB` column on
   `project_workflow_step_runs`
2. `persistence/workspace-roots.clj` — CRUD functions
3. Seed the Engineering Review step with `requires = [{"kind": "code_context_path"}]`
4. HTTP routes: `/workspace-roots` and `PUT /projects/:id/local-paths`
5. Verify round-trip: register a root via API, confirm it persists

**Phase 2 — File reading utility**

6. `utils/local-files.clj` — `NOISE-DIRS`, `binary-extension?`, `lock-file?`,
   `confined-path?`, `safe-slurp`, `path-info`, `tier-score`, `prioritize-files`,
   `collect-recursive`, `read-local-paths`, `format-code-context`
7. Unit tests:
   - `tier-score`: takes plain maps, not Files — Tier 1 entrypoints, Tier 2 `src/`,
     Tier 3 source elsewhere, Tier 4 config; no filesystem required
   - `collect-recursive`: noise dir subtree excluded, 2000-file cap, symlink traversal rejected
   - `prioritize-files`: correct sort order across tiers and within a tier (depth then alpha)
   - `read-local-paths`: returns file records (not concatenated string); binary skip,
     lock file skip, per-file 10k cap, total 50k cap, `:not-read` populated when cap reached
   - `format-code-context`: assembles `<code_context>` from records; "cap reached — not read"
     distinct from "skipped"

**Phase 3 — Matching**

8. `utils/path-matching.clj` — `normalize`, `score-candidate`, `find-best-match`,
   `scan-workspace-roots`
9. Unit tests: exact match, title-contains, no match, multiple candidates above
   threshold, score tie-breaking

**Phase 4 — Requirement resolution and prompt injection**

10. `resolve-code-context-path` (pure, no `!`) in `llm_executor.clj` — takes project
    and workspace-roots, returns `{:path}` or `{:needs-input true :candidates [...]}`
11. `requirement-resolvers` registry map in `llm_executor.clj`
12. `resolve-requirements!` generic resolver in `llm_executor.clj` — reads
    `step-def.requires`, calls registered resolver per kind, writes `pending_inputs`
    on `:needs-input`, returns `:needs-user-input` or `{:resolved {...}}`
13. Update `execute-step!` to call `resolve-requirements!` before dispatch;
    update `execute-step-by-kind! :score` to consume resolved inputs (no agent-type branch)
14. Define `ScoreStepOutput` Malli schema; update executor to validate output on write
15. `build-scoring-user-prompt-with-code` in `scoring/agents.clj`; update
    `engineering-lead-system-prompt` with code-context guidance
16. End-to-end test: Engineering Review step has `requires`; workspace root matches;
    review includes `<code_context>` in Anthropic call (verify via log trace)

**Phase 5 — Pause-and-resume and state machine fix**

17. Fix `terminal?` predicate to exclude `awaiting_input`; verify `run-parallel-steps!`
    naturally blocks until paused steps resume
18. `pending_inputs.kind = "code_context_path"` branch in `respond-to-step!`;
    clears `pending_inputs`, resets `status` to `"pending"` — `output_kind` untouched
19. Full cycle test: no workspace root → step pauses → user submits path → step
    re-runs with code context → score output matches `ScoreStepOutput` schema

**Phase 6 — Frontend**

20. Workspace roots settings section
21. `code_context_path` input renderer with candidate radio buttons
22. Score card `context_path` attribution note

---

## Risks and mitigations

**`awaiting_input` must not be treated as terminal**

If `terminal?` incorrectly includes `awaiting_input`, `run-parallel-steps!` would
consider a paused step done and allow the fan-in judge to run against a missing
score. The `terminal?` predicate fix (Phase 5, step 17) is load-bearing and must
be verified before end-to-end testing. The test: pause the Engineering step;
assert the judge does not run; assert the workflow run status is `awaiting_input`.

**Workspace root scanning and large directories**

If the user registers `/` or a directory with thousands of immediate children, the
candidate scan is slow. Validate at registration time that the path exists and is
a directory. Cap the candidate list at 50 entries and log a warning when truncated.

**False positive auto-selection for generic project titles**

A project titled "server" matches any directory named `server`. Consider raising
the auto-use threshold to 0.85 when multiple candidates have similar scores. The
exact vs. substring distinction in the algorithm reduces most false positives.

