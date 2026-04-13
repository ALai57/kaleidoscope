# Implementation Plan: Atomic Tasks

## Overview

When a user creates a project, a workflow runs that breaks the project down into **atomic, actionable
tasks** — small, self-contained to-do items completable in under half a day. Tasks are prioritizable
on a drag-to-reorder list and categorized by type (research, development, purchase, etc.).

If the project description is too vague, the app asks targeted clarifying questions before generating
tasks. Where there are unknowns, the system generates dedicated `unknown_resolution` tasks with a
suggested time-box for investigation.

A new `task_planner` agent type handles both clarification and task generation. The existing workflow
system is extended so that a "Break Down Into Tasks" step produces and persists tasks — not just text.

---

## Database Schema

### Migration 8 — project_tasks

```sql
CREATE TABLE project_task_generation_runs (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id  UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id     TEXT NOT NULL,
  created_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE project_tasks (
  id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id            UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id               TEXT NOT NULL,
  title                 TEXT NOT NULL,
  description           TEXT,
  task_type             TEXT NOT NULL DEFAULT 'action',
    -- open label: well-known values are action, research, purchase, review, development,
    -- unknown_resolution — but not validated as a closed set; new labels may emerge from use
  status                TEXT NOT NULL DEFAULT 'pending',
    -- pending | in_progress | completed | skipped
  position              INT NOT NULL DEFAULT 0,     -- authoritative display order; drag-reorder updates this directly
  estimated_minutes     INT,                        -- agent or user-supplied effort estimate
  generation_run_id     UUID REFERENCES project_task_generation_runs(id),
    -- links all tasks from a single generation invocation; NULL for user-created tasks;
    -- used for selective deletion when regenerating (delete by run, not by provenance flag)
  workflow_step_run_id  UUID REFERENCES project_workflow_step_runs(id),
  created_at            TIMESTAMPTZ DEFAULT now(),
  updated_at            TIMESTAMPTZ DEFAULT now()
);

CREATE INDEX ON project_tasks (project_id, status);
CREATE INDEX ON project_tasks (project_id, position);
```

No parent/child task dependency for MVP — keep it flat. Complexity is in the task types.

**On ordering:** `position` is the single authoritative ordering. There is no separate `priority`
column. Priority is a judgment expressed by drag-reorder — a user moving a task up *is* the act of
prioritising it. Storing `priority` and `position` as independent integers complects two things that
want to be one ordered sequence and produces irresolvable conflicts between them.

**On deletion by generation:** `generation_run_id` links all tasks produced in one generation
invocation. Regenerating tasks deletes the prior generation run's tasks by run ID, not by a
`generated_by` flag. This avoids using provenance as a behavior key — a task a user edited after
generation should not inherit deletion semantics from its origin.

---

## Task Types

`task_type` is an open label — a plain string. The system does not validate it against a closed
enum. Well-known values and their UI treatment are listed below, but the LLM or user may produce
other labels and the system will render them as generic tasks. This lets the vocabulary grow through
use without requiring a code change each time a new category of work appears.

| well-known value | meaning | example |
|-----------------|---------|---------|
| `action` | General to-do | "Schedule kickoff meeting" |
| `research` | Discovery / investigation | "Research competitor pricing models" |
| `purchase` | Buy or acquire something | "Purchase domain name" |
| `review` | Review a deliverable | "Review initial wireframes with stakeholders" |
| `development` | Build / code / configure | "Implement auth middleware" |
| `unknown_resolution` | Investigate a known unknown; time-boxed | "Spend 1 hr clarifying API rate limit constraints" |

`unknown_resolution` is the only type with a UI affordance difference (warning colour, `estimated_minutes`
prominently displayed) — but it is not special-cased at the schema or validation layer.

---

## API Contracts

All routes scoped under `/projects/:id/tasks`. Gated by auth.

### Tasks CRUD

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/projects/:id/tasks` | Returns all tasks ordered by `position ASC`. Optional `?status=pending` filter. |
| `POST` | `/projects/:id/tasks` | `{title, description?, task_type, estimated_minutes?}` — position assigned as last in list |
| `PUT` | `/projects/:id/tasks/:task-id` | Partial update: `{title?, description?, status?, task_type?, estimated_minutes?}` — position is not updated here; use the reorder endpoint |
| `DELETE` | `/projects/:id/tasks/:task-id` | Hard delete |
| `PUT` | `/projects/:id/tasks/reorder` | `[{id, position}]` — full replacement of the ordered sequence; this is the only way to change position, keeping ordering as a first-class operation |

### Clarification (SSE)

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/tasks/clarify` | `{message}` — sends a user turn to the task_planner agent in clarification mode. Streams `token` events then a `done` event. Messages stored in `project_conversations` with `agent_type='task_planner'`. |

The `done` event carries a structured payload — not prose:

```json
{ "event": "done", "data": { "ready_for_generation": true } }
```

The server determines `ready_for_generation` from the LLM's structured output (prompt instructs the
model to return a JSON envelope: `{"ready": true, "reply": "..."}` which the server unpacks before
streaming the reply tokens). The client reads a fact; it does not parse natural language.

Reuses the existing `project_conversations` table with `agent_type='task_planner'`.

> **Note on conversation semantics:** clarification turns have different retention semantics than
> coaching/PM conversations — they are scaffolding for task generation, not ongoing advisory records.
> The fetch endpoint for the "Conversation" tab must filter by `agent_type` so clarification turns
> do not appear alongside PM/coach conversations. This distinction should be documented in the API
> but both types remain in the same table.

### Task Generation (SSE)

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/tasks/generate` | Generates tasks from project description + any existing `task_planner` conversation messages. Streams `token` events while reasoning, then a `tasks_generated` event with the full list, then `done`. Tasks are persisted server-side before `tasks_generated` fires. Optional `?replace=true` to clear existing agent-generated tasks before inserting new ones. |

**`tasks_generated` event payload:**
```json
{
  "event": "tasks_generated",
  "data": [
    {"id": "uuid", "title": "...", "taskType": "research", "estimatedMinutes": 30, ...},
    {"id": "uuid", "title": "...", "taskType": "unknown_resolution", "estimatedMinutes": 60, ...}
  ]
}
```

---

## Agent: Task Planner

A new agent type `task_planner` seeded into `agent_definitions` alongside coach / pm / engineering_lead.

```
display_name: "Task Planner"
avatar: "📋"
agent_type: "task_planner"
```

### Clarification system prompt

```
You are an experienced project manager and GTD practitioner. Your goal is to help the user
clarify a vague project idea into something concrete enough to generate an actionable task list.

Ask 2–3 focused, specific questions per turn. Do NOT ask generic questions like "What is the
goal?" — instead probe the actual unknowns in the project description. After each response,
decide whether you have enough information to generate tasks.

You MUST respond with a JSON object (no other text):
{
  "ready": true | false,
  "reply": "<your message to the user>"
}

Set "ready" to true when you have enough context to produce a useful task list.
Keep "reply" brief. One question per paragraph.
```

The server extracts `ready` and `reply` from this JSON, streams `reply` as tokens to the client,
and includes `{"ready_for_generation": ready}` in the `done` event. The LLM never emits prose
directly — only structured output that the server unpacks. This eliminates the hidden dependency on
a specific phrase appearing in natural language output.

### Task generation system prompt

```
You are an expert project planner and GTD practitioner. Break the following project into atomic,
actionable tasks. Rules:
- Each task must be completable in under half a day (≤ 4 hours) by one person.
- Categorize each task as one of: action, research, purchase, review, development, unknown_resolution.
- For anything unclear or unknown, create an unknown_resolution task with an estimated_minutes value
  representing how long the user should spend investigating before generating more tasks.
- Order tasks by recommended execution order (position 0 = first).
- Output ONLY a JSON array. No prose before or after.

JSON schema per task:
{
  "title": string,          // imperative verb phrase, ≤ 80 chars
  "description": string,    // 1–2 sentences of context, optional
  "task_type": string,      // one of the allowed types above
  "estimated_minutes": int  // required for unknown_resolution, optional otherwise
}
```

**Prompt construction** (in `tasks/planner.clj`):

1. System prompt (generation variant)
2. Project context block: `title`, `description`, score run summaries, existing notes
3. If prior `task_planner` conversation messages exist: append them as context
4. User turn: "Generate the task list."

---

## Backend Implementation

### Namespace layout

```
src/kaleidoscope/
  models/
    tasks.cljc                  Malli schemas: Task, TaskStatus; task_type is [:string] not an enum
  persistence/
    tasks.clj                   HoneySQL queries: CRUD, bulk-reorder, list-by-project
  tasks/
    planner.clj                 LLM calls: clarify! (streaming Q&A) + generate-tasks! (streaming → JSON parse → insert)
  api/
    tasks.clj                   Orchestration: list-tasks, create-task!, update-task!, delete-task!, reorder-tasks!, generate-tasks!, clarify!
  http_api/
    tasks.clj                   Reitit routes + Ring handlers for /projects/:id/tasks/*
```

Wire `tasks.clj` HTTP routes into `http_api/kaleidoscope.clj` under the existing `/projects/:id` subtree.

### `tasks/planner.clj`

```clojure
(defprotocol ITaskPlanner
  (clarify!       [this db project conversation-history user-message output-stream])
  (generate-tasks! [this db project conversation-history]))
```

**`clarify!`**:
1. Builds message list from `conversation-history` (prior `task_planner` turns) + new `user-message`
2. Streams tokens to `output-stream` using SSE helpers from `workflows/executor.clj`
3. Persists the user turn + assistant response to `project_conversations` (agent_type = `task_planner`)

**`generate-tasks!`**:
1. Creates a `project_task_generation_runs` row and retains its `id`
2. Builds prompt (see above)
3. Calls Claude non-streaming to get full JSON response
4. Parses the JSON array
5. Bulk-inserts tasks into `project_tasks` with `generation_run_id` set to the run ID created in step 1
6. Returns the inserted task rows

The HTTP handler streams a brief "Analysing project…" progress message to the client before
calling `generate-tasks!`, then emits the `tasks_generated` SSE event with the returned tasks.
Two separate LLM calls (one streaming analysis, one structured JSON) are explicitly avoided —
one call, one structured output, one source of truth.

### `persistence/tasks.clj`

Key queries (all using HoneySQL, following existing patterns):

```clojure
(defn list-tasks [db project-id]
  ;; SELECT * WHERE project_id = ? ORDER BY position ASC

(defn create-task! [db task-map]
  ;; INSERT INTO project_tasks ... RETURNING *

(defn update-task! [db task-id updates]
  ;; UPDATE project_tasks SET ... WHERE id = ? RETURNING *

(defn delete-task! [db task-id]
  ;; DELETE FROM project_tasks WHERE id = ?

(defn bulk-reorder! [db project-id positions]
  ;; For each {id, position}: UPDATE project_tasks SET position = ?, updated_at = now() WHERE id = ?
  ;; Run in a transaction

(defn get-latest-generation-run [db project-id]
  ;; SELECT id FROM project_task_generation_runs
  ;; WHERE project_id = ? ORDER BY created_at DESC LIMIT 1

(defn delete-generation-run-tasks! [db generation-run-id]
  ;; DELETE FROM project_tasks WHERE generation_run_id = ?
  ;; Used by generate with ?replace=true: caller fetches the latest run id, then calls this
```

### Workflow Executor Integration

Workflow steps gain an `:output-kind` field — `:text` (default) or `:tasks`. The executor
dispatches on this with a multimethod, keeping the executor itself free of knowledge about what
any specific agent type does:

```clojure
(defmulti execute-step-by-kind!
  (fn [_executor _db _project step-run _output-stream]
    (:output-kind step-run :text)))

(defmethod execute-step-by-kind! :text [executor db project step-run output-stream]
  ;; existing path: call agent, stream tokens, persist text output
  )

(defmethod execute-step-by-kind! :tasks [executor db project step-run output-stream]
  ;; call ITaskPlanner/generate-tasks!, stream tokens, emit tasks_generated event,
  ;; persist tasks, set step_run.output to summary string
  )
```

`workflow_steps` gets an `output_kind TEXT NOT NULL DEFAULT 'text'` column (migration 8 or a
separate migration). The "Break Down Into Tasks" seed step has `output_kind='tasks'`.

New agent types that produce structured output (scores, skill trees, etc.) follow the same pattern:
add a new `defmethod`, not a new `if` branch. The executor's abstraction remains intact.

### Default Workflow Update

Add step to the seeded "Feature Development" workflow in `api/workflows.clj`:

| position | name | description | agent_type |
|----------|------|-------------|------------|
| 0 | Evaluate product idea | Evaluate the product idea for clarity, market fit, and user value. | pm |
| 1 | Evaluate Engineering architecture | Evaluate engineering architecture. If architecture score is below 5, ask the Engineering Architect to suggest ways to implement and re-score. | engineering_lead |
| 2 | Break Down Into Tasks | Break the project into atomic, actionable next steps prioritized by urgency. For any unknowns, create time-boxed investigation tasks. | task_planner |

This step runs after scoring and produces the initial task list.

---

## Frontend Implementation

### New types (`src/types/tasks.ts`)

```typescript
// task_type is an open label. Well-known values are listed here for UI treatment
// (icons, colours) but the type is not a closed union — unknown values render as generic tasks.
export type WellKnownTaskType =
  | 'action'
  | 'research'
  | 'purchase'
  | 'review'
  | 'development'
  | 'unknown_resolution';

export type TaskStatus = 'pending' | 'in_progress' | 'completed' | 'skipped';

export interface ProjectTask {
  id: string;
  projectId: string;
  title: string;
  description?: string;
  taskType: string;             // open label; use WellKnownTaskType for UI lookup with fallback
  status: TaskStatus;
  position: number;             // authoritative ordering; drag-reorder updates this via /reorder
  estimatedMinutes?: number;
  generationRunId?: string;     // present when task was agent-generated; null for user-created tasks
  workflowStepRunId?: string;
  createdAt: string;
  updatedAt: string;
}
```

### New API client (`src/api/tasks.ts`)

```typescript
getTasks(projectId, token?)                                      → ProjectTask[]
createTask(projectId, body: {title, description?, task_type, estimated_minutes?}, token?)  → ProjectTask
updateTask(projectId, taskId, body: {title?, description?, status?, task_type?, estimated_minutes?}, token?) → ProjectTask
deleteTask(projectId, taskId, token?)                            → void
reorderTasks(projectId, positions: {id:string,position:number}[], token?) → void
  // positions is the full new sequence; server replaces all positions in a transaction
generateTasks(projectId, replace?: boolean, token?)              → AsyncGenerator<{event, data}>
  // done event: { event: "done", data: { tasks_generated: ProjectTask[] } }
clarifyProject(projectId, message: string, token?)               → AsyncGenerator<{event, data}>
  // done event: { event: "done", data: { ready_for_generation: boolean } }
```

React Query keys:
- `['projects', id, 'tasks']` — invalidated after create / update / delete / generate

### New components (`src/components/tasks/`)

| Component | Purpose |
|-----------|---------|
| `TaskList.tsx` | Drag-to-reorder list using `@dnd-kit/sortable`. Groups by status (Pending first, then Done). Accepts `tasks: ProjectTask[]` and callbacks. |
| `TaskItem.tsx` | Row: drag handle, checkbox (toggles `pending` ↔ `completed`), task type badge, title, effort chip (e.g. "30 min"), delete icon. Unknown_resolution tasks styled distinctly (warning colour). |
| `TaskTypeChip.tsx` | MUI Chip with icon + label for each TaskType. Icons: 🔬 research, 💳 purchase, 👁 review, 💻 development, ❓ unknown_resolution, ✅ action. |
| `TaskForm.tsx` | Create/edit dialog. Fields: title (required), description, task_type (Select), estimated_minutes (number, shown when type = unknown_resolution or user wants it). |
| `ClarificationChat.tsx` | Streaming Q&A panel. Shows `task_planner` conversation messages. Input box at bottom. On submit: calls `clarifyProject` SSE, appends streamed tokens as assistant message. Reads `ready_for_generation` from the `done` event payload and surfaces it to the parent via a callback — does not parse prose. |
| `TaskGenerationPanel.tsx` | Orchestrator component. On mount: calls `GET /tasks/clarify-status`. If `ready_for_generation: false`: shows `ClarificationChat`. If `ready_for_generation: true`: shows "Generate Tasks" button. User can always bypass to Generate regardless. On click: streams `generateTasks`, shows spinner, inserts tasks into list on `done` event. |

### Route and page integration

**Route**: No new top-level page — tasks live in a new tab on `ProjectDetailPage.tsx`.

Add a **"Tasks"** tab alongside the existing tabs (Scores, Notes, Conversation, Skills, Workflow):

```tsx
// In ProjectDetailPage.tsx
<Tab label="Tasks" value="tasks" icon={<ChecklistIcon />} />

// Tab panel
<TabPanel value="tasks">
  <TaskGenerationPanel projectId={id} />
  <QuickAddTaskInput projectId={id} />   {/* single-line inline add */}
  <TaskList tasks={tasks} ... />
</TabPanel>
```

`QuickAddTaskInput` is a small inline component (text field + Enter-to-submit) for adding tasks
without opening the full `TaskForm` dialog. Task type defaults to `action`.

### Drag-to-reorder

Use `@dnd-kit/sortable` (already in the MUI ecosystem, lighter than react-beautiful-dnd):

- Reorder is optimistic: update local state immediately, call `reorderTasks` in background.
- On error: revert to server state by invalidating the query.

---

## Vagueness Detection and Clarification State

Whether the project needs clarification before task generation is determined server-side, not by a
client word-count heuristic. The `TaskGenerationPanel` on mount calls:

```
GET /projects/:id/tasks/clarify-status
→ { ready_for_generation: boolean, has_prior_conversation: boolean }
```

The server computes `ready_for_generation` by re-evaluating the existing clarification conversation
(or the absence of one). If no conversation exists and the project description is short, it returns
`false`. If the last assistant turn in the conversation had `ready: true`, it returns `true`.

This keeps vagueness assessment on the server where it can use the full project context, and the
client reads a typed boolean — not a word count and not a substring match against prose.

**Clarification state transitions:**

```
no conversation            →  ready_for_generation: false  (show ClarificationChat)
conversation, ready: false →  ready_for_generation: false  (continue chat)
conversation, ready: true  →  ready_for_generation: true   (show Generate button)
user bypasses clarification →  user can always click Generate regardless of state
```

The user can always bypass clarification and generate tasks directly — clarification is a
recommendation, not a gate.

---

## Testing Plan

### Backend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | `generate-tasks!` parses LLM JSON and returns task maps | Mock LLM response, assert count + types |
| Unit | `clarify!` appends user + assistant messages to conversations | Mock LLM, assert DB inserts |
| Unit | `bulk-reorder!` is transactional (all or nothing) | Embedded-postgres |
| Integration | Tasks CRUD endpoints + auth isolation | Ring mock + embedded-postgres |
| Integration | `POST /generate` streams tokens then fires `tasks_generated` event with persisted IDs | Embedded-postgres + mock planner |
| Integration | Workflow executor dispatches `task_planner` step type correctly | Mock planner, assert step_run output |
| Integration | Default workflow seeds 3 steps including `task_planner` step | Seed + query |

### Frontend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | `TaskTypeChip` renders correct icon per type | Vitest + Testing Library |
| Unit | `TaskItem` checkbox toggles status | Vitest, synthetic click |
| Unit | `TaskList` drag-drop reorder calls `onReorder` with new positions | Vitest, dnd-kit test utils |
| Integration | `TaskGenerationPanel` shows `ClarificationChat` for short descriptions | MSW, short-description fixture |
| Integration | `ClarificationChat` appends streamed tokens as assistant message | MSW streaming mock |
| Integration | `generateTasks` inserts tasks after `tasks_generated` event | MSW |
| Integration | Optimistic drag-drop reorder reverts on API error | MSW 500 response |
| Integration | Tasks tab shows task count badge | MSW, count from fixture |

---

## Build Sequence

1. **Migration 8** — `project_task_generation_runs` + `project_tasks` tables + indexes; add `output_kind TEXT NOT NULL DEFAULT 'text'` column to `workflow_steps`
2. **Malli schemas** — `models/tasks.cljc` (`task_type` as `[:string]`, not a closed enum)
3. **Persistence layer** — `persistence/tasks.clj` (CRUD + bulk-reorder + `get-latest-generation-run` + `delete-generation-run-tasks!`)
4. **Task planner LLM** — `tasks/planner.clj` (`clarify!` with structured JSON output + `generate-tasks!` with mock impl)
5. **API layer** — `api/tasks.clj` (orchestration + `get-clarify-status` logic)
6. **HTTP routes** — `http_api/tasks.clj` (including `GET /tasks/clarify-status`) + wire into `kaleidoscope.clj`
7. **Seed `task_planner` agent** in `api/agents.clj` default seeding
8. **Extend workflow executor** — `defmulti execute-step-by-kind!` + `:tasks` defmethod; retire `if`-branch approach
9. **Update default workflow seed** — add "Break Down Into Tasks" step at position 2 with `output_kind='tasks'`
10. **Frontend: types + API client** — `src/types/tasks.ts` (open `taskType: string`), `src/api/tasks.ts`
11. **Frontend: `TaskTypeChip` + `TaskItem` + `TaskList`** — base display + drag-drop (position-only ordering)
12. **Frontend: `ClarificationChat`** — streaming Q&A; reads `ready_for_generation` from `done` event (not prose)
13. **Frontend: `TaskGenerationPanel`** — calls `GET /tasks/clarify-status` on mount; bypass always available
14. **Frontend: wire into `ProjectDetailPage`** — Tasks tab, `QuickAddTaskInput`, React Query integration
