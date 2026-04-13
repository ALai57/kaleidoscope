# Implementation Plan: Atomic Tasks

## Overview

Every project has a task list. This is not optional and requires no user action to initiate — the
app generates the list automatically when a project is created. The core value proposition is that
a user describes what they want to accomplish, and the app acts as a coach that structures the work
into atomic, completable next actions.

Tasks are small, self-contained to-do items completable in under half a day. They are ordered by
recommended execution sequence and categorized by type (research, development, purchase, etc.).
Where there are unknowns, the system generates `investigate` tasks with a suggested time-box.

**Task generation is event-driven, not user-initiated:**
- A project is created → the default workflow starts immediately; tasks are generated as part of it
- The task list runs low (≤ 3 pending tasks remain) → the app prompts: "Ready to plan your next steps?"

The Tasks tab is a **view** of the current task list, not a control panel for generation. There is
no "Generate Tasks" button at rest. The user's job is to work through their tasks; the app's job is
to ensure there are always meaningful next actions available.

**Project creation is never blocked.** `POST /projects` always creates the project and starts the
default workflow immediately. Clarification is the **first step of the workflow** — if the
description is thin, the workflow pauses at that step (`awaiting_input`), presents questions to the
user via the existing workflow UI, waits for their answers, then appends those answers to
`project.description` before continuing. The enriched description then flows into all subsequent
steps: scoring, architecture evaluation, and task generation alike.

A new `task_planner` agent type handles the clarification step and the task generation step. The
workflow executor is extended so that steps can produce structured output (tasks) — not just text.

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
    -- investigate — but not validated as a closed set; new labels may emerge from use
  status                TEXT NOT NULL DEFAULT 'pending',
    -- pending | in_progress | completed
    -- No 'skipped' status: tasks the user decides not to do should be deleted.
    -- Skipped accumulated visual noise with no clear semantics.
  position              INT NOT NULL DEFAULT 0,     -- authoritative display order; drag-reorder updates this directly
  estimated_minutes     INT,                        -- agent or user-supplied effort estimate
  generation_run_id     UUID REFERENCES project_task_generation_runs(id),
    -- links all tasks from a single generation invocation; NULL for user-created tasks;
    -- useful for audit and traceability; replace mode has been removed (planNextSteps always
    -- appends, and initial generation has no prior tasks to replace)
  workflow_step_run_id  UUID REFERENCES project_workflow_step_runs(id),
  created_at            TIMESTAMPTZ DEFAULT now(),
  updated_at            TIMESTAMPTZ DEFAULT now()
);

-- updated_at must be kept current on every UPDATE; a DEFAULT only fires on INSERT.
CREATE OR REPLACE FUNCTION set_updated_at()
RETURNS TRIGGER AS $$
BEGIN NEW.updated_at = now(); RETURN NEW; END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER project_tasks_updated_at
BEFORE UPDATE ON project_tasks
FOR EACH ROW EXECUTE FUNCTION set_updated_at();

CREATE INDEX ON project_tasks (project_id, status);
CREATE INDEX ON project_tasks (project_id, position);
```

No parent/child task dependency for MVP — keep it flat. Complexity is in the task types.

**On ordering:** `position` is the single authoritative ordering. There is no separate `priority`
column. The list represents **execution order** — the sequence the user intends to work through
the tasks — not abstract importance. The UI should label the list "What to do next" or similar,
never "Priority list," to avoid the implication that the top item is necessarily the most important.
A user drags a task up to say "I'll do this sooner," not "this matters more." These are often the
same, but the mental model the app surfaces should be the former.

**On generation runs:** `generation_run_id` links all tasks produced in one generation invocation,
providing audit traceability. There is no replace mode — generation always appends — so tasks are
never deleted by run ID. The field is informational only.

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
| `investigate` | Figure out a known unknown; time-boxed | "Spend 1 hr figuring out API rate limit constraints" |

`investigate` is the only type with a UI affordance difference (warning colour, `estimated_minutes`
prominently displayed) — but it is not special-cased at the schema or validation layer.

Tasks that won't be done should be **deleted**, not given a special status. See status design below.

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

### Clarification — workflow step, not a separate API

Clarification is not a Tasks endpoint and not a separate project API flow. It is the **first step
of the default workflow**, using the existing `awaiting_input` run status to pause and wait for
user input. The workflow UI already handles this — no new UI surface is needed.

When the clarification step runs and the description is thin:
1. The step calls the task_planner `assess-description` function
2. If `ready: false`, the step sets `step_run.status = 'awaiting_input'` and `step_run.output = questions JSON`
3. The workflow run status becomes `awaiting_input`
4. The user sees the questions in the Workflow tab and answers them
5. Submitting answers calls the existing `POST /projects/:id/workflow-runs/:run-id/steps/:step-run-id/respond` endpoint with `{answers: [...]}`
6. The server appends the answers verbatim to `project.description`, marks the step complete, and the workflow advances

`project.description` is the single enriched record — there is no separate clarification store to
reconcile. Scoring, architecture evaluation, and task generation all operate on the same field.

### Task Generation (SSE)

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/tasks/generate` | Generates tasks and appends them to the existing list. Always appends — there is no replace mode. Called by the workflow executor (via function, not HTTP) on initial creation, and by the browser when the user acts on the "Plan next steps" nudge. Streams `token` events then fires `tasks_generated` and `done`. |

`planNextSteps` and the workflow's "Break Down Into Tasks" step both ultimately call this endpoint.
There is only one generation path and one mode — append. On initial project creation there are no
prior tasks, so append is equivalent to a fresh generation.

**`tasks_generated` event payload:**
```json
{
  "event": "tasks_generated",
  "data": [
    {"id": "uuid", "title": "...", "taskType": "research", "estimatedMinutes": 30, ...},
    {"id": "uuid", "title": "...", "taskType": "investigate", "estimatedMinutes": 60, ...}
  ]
}
```

### Low-task nudge

| Method | Path | Notes |
|--------|------|-------|
| `GET` | `/projects/:id/tasks/status` | Returns `{pending_count, total_count}` — raw facts only. The client decides whether to show the nudge (currently: `pending_count ≤ 3`). Keeping the threshold client-side means it can be tuned without a server change. |

---

## Agent: Task Planner

A new agent type `task_planner` seeded into `agent_definitions` alongside coach / pm / engineering_lead.

```
display_name: "Task Planner"
avatar: "📋"
agent_type: "task_planner"
```

### Clarification system prompt — used in the "Clarify Description" workflow step

This prompt runs as the first step of the default workflow, after project creation. If the
description is sufficient, the step completes immediately and the workflow advances. If not, the
step pauses for user input via the existing `awaiting_input` mechanism.

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

The server extracts `ready` and `reply` from this JSON. If `ready: true`, the workflow step
completes immediately and advances. If `ready: false`, the step sets its status to `awaiting_input`
and emits `reply` (the questions) as the step output — displayed in the workflow UI for the user
to answer. There is no SSE stream for this step; it is a synchronous assessment followed by a
workflow state transition.

### Task generation system prompt

```
You are an expert project planner and GTD practitioner. Break the following project into atomic,
actionable tasks. Rules:
- Each task must be completable in under half a day (≤ 4 hours) by one person.
- Categorize each task as one of: action, research, purchase, review, development, investigate.
- For anything unclear or unknown, create an investigate task with an estimated_minutes value
  representing how long the user should spend figuring it out before generating more tasks.
- Order tasks by recommended execution order (position 0 = first).
- Output ONLY a JSON array. No prose before or after.

JSON schema per task:
{
  "title": string,          // imperative verb phrase, ≤ 80 chars
  "description": string,    // 1–2 sentences of context, optional
  "task_type": string,      // one of the allowed types above
  "estimated_minutes": int  // required for investigate, optional otherwise
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
    planner.clj                 ITaskPlanner protocol: assess-description + generate-task-list (pure return-value functions)
  api/
    tasks.clj                   Orchestration: list-tasks, create-task!, update-task!, delete-task!, reorder-tasks!, clarify-description-step!, run-task-generation!
  http_api/
    tasks.clj                   Reitit routes + Ring handlers for /projects/:id/tasks/*
```

Wire `tasks.clj` HTTP routes into `http_api/kaleidoscope.clj` under the existing `/projects/:id` subtree.

### `tasks/planner.clj`

```clojure
(defprotocol ITaskPlanner
  (assess-description [this project conversation-history]
    "Returns {:ready bool :questions [...] :reply string}.
     Pure domain call — no DB writes, no streaming. Caller handles persistence and I/O.")
  (generate-task-list [this project conversation-history]
    "Returns a seq of task maps {:title :description :task_type :estimated_minutes}.
     Pure domain call. Caller creates the generation run, bulk-inserts tasks, handles SSE."))
```

The protocol describes *what* the planner does, not *how* outputs are delivered. `db` and
`output-stream` are not protocol concerns — they are injected into the implementing record at
construction time or handled by the orchestration layer in `api/tasks.clj`.

**Orchestration in `api/tasks.clj`:**

`clarify-description-step!` (called by the workflow executor for the clarification step):
1. Calls `assess-description` — returns `{:ready bool :questions [...] :reply string}`
2. If `ready: true` → marks step complete, workflow advances
3. If `ready: false` → sets step output to `questions`, sets step status to `awaiting_input`
4. When user submits answers → appends answers verbatim to `project.description`, marks step complete

`run-task-generation!` (called by the workflow executor AND by the nudge HTTP handler):
1. Creates a `project_task_generation_runs` row
2. Calls `generate-task-list` — returns seq of task maps
3. Bulk-inserts tasks with `generation_run_id`
4. Streams `tasks_generated` SSE event with the inserted rows
5. Returns the inserted task rows

One call, one structured output. No streaming analysis pass.

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

(defn list-generation-runs [db project-id]
  ;; SELECT * FROM project_task_generation_runs
  ;; WHERE project_id = ? ORDER BY created_at DESC
  ;; Used for audit/traceability; replace mode has been removed
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
  ;; delegates to run-task-generation! in api/tasks.clj:
  ;; creates generation run, calls generate-task-list, bulk-inserts, emits tasks_generated SSE event,
  ;; sets step_run.output to summary string
  )
```

`workflow_steps` gets an `output_kind TEXT NOT NULL DEFAULT 'text'` column (migration 8 or a
separate migration). The "Break Down Into Tasks" seed step has `output_kind='tasks'`.

New agent types that produce structured output (scores, skill trees, etc.) follow the same pattern:
add a new `defmethod`, not a new `if` branch. The executor's abstraction remains intact.

### Default Workflow Update

Add step to the seeded "Feature Development" workflow in `api/workflows.clj`:

| position | name | description | agent_type | output_kind |
|----------|------|-------------|------------|-------------|
| 0 | Clarify Description | If the project description is too brief to generate useful tasks, ask targeted questions to enrich it. Answers are appended to the project description before any other step runs. | task_planner | clarify |
| 1 | Evaluate product idea | Evaluate the product idea for clarity, market fit, and user value. | pm | text |
| 2 | Evaluate Engineering architecture | Evaluate engineering architecture. If architecture score is below 5, ask the Engineering Architect to suggest ways to implement and re-score. | engineering_lead | text |
| 3 | Break Down Into Tasks | Break the project into atomic, actionable next steps prioritized by urgency. For any unknowns, create time-boxed investigation tasks. | task_planner | tasks |

Step 0 (`clarify`) completes immediately if the description is sufficient; users never see it.
Step 3 (`tasks`) produces the task list.

A new `output_kind = 'clarify'` is added to the `defmulti` dispatch alongside `:text` and `:tasks`:

```clojure
(defmethod execute-step-by-kind! :clarify [executor db project step-run output-stream]
  ;; calls assess-description; if ready: mark complete and advance
  ;; if not ready: set awaiting_input, emit questions as step output, halt workflow
  )
```

When the user submits answers (via existing workflow respond endpoint):
- Server appends answers verbatim to `project.description`
- Step marked complete
- Workflow resumes from step 1

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
  | 'investigate';

// No 'skipped' — tasks the user won't do should be deleted, not archived.
export type TaskStatus = 'pending' | 'in_progress' | 'completed';

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
// Task list management
getTasks(projectId, token?)                                          → ProjectTask[]
createTask(projectId, body: {title, description?, task_type, estimated_minutes?}, token?) → ProjectTask
updateTask(projectId, taskId, body: {title?, description?, status?, task_type?, estimated_minutes?}, token?) → ProjectTask
deleteTask(projectId, taskId, token?)                                → void
reorderTasks(projectId, positions: {id:string,position:number}[], token?) → void
  // positions is the full new sequence; server replaces all positions in a transaction

// Low-task nudge
getTaskStatus(projectId, token?)                                     → {pending_count: number, total_count: number}
  // raw facts only; client decides whether to show nudge (currently: pending_count <= 3)
planNextSteps(projectId, token?)                                     → AsyncGenerator<{event, data}>
  // calls POST /tasks/generate (always appends; no mode parameter)
  // done event: { event: "done", data: { tasks_generated: ProjectTask[] } }
  // this is the only user-facing generation call; named for what it does, not how it works

// Clarification is handled through the existing workflow respond endpoint in src/api/workflows.ts
// respondToWorkflowStep(projectId, runId, stepRunId, {answers: string[]}, token?) → WorkflowRun
```

React Query keys:
- `['projects', id, 'tasks']` — invalidated after create / update / delete / generate

### New components (`src/components/tasks/`)

| Component | Purpose |
|-----------|---------|
| `TaskList.tsx` | Drag-to-reorder list using `@dnd-kit/sortable`. Pending tasks first, completed below a divider. Accepts `tasks: ProjectTask[]` and callbacks. |
| `TaskItem.tsx` | Row: drag handle, checkbox (toggles `pending` ↔ `completed`), task type badge, title, effort chip (e.g. "30 min"), delete icon. `investigate` tasks styled with a warning colour and the effort estimate shown prominently. No skip affordance — delete is the only removal action. |
| `TaskTypeChip.tsx` | MUI Chip with icon + label for each well-known TaskType. Icons: 🔬 research, 💳 purchase, 👁 review, 💻 development, 🔍 investigate, ✅ action. Unknown labels render as a plain chip with no icon. |
| `TaskForm.tsx` | Create/edit dialog. Fields: title (required), description, task_type (Select), estimated_minutes. |
| `PlanNextStepsNudge.tsx` | Polls `GET /tasks/status`. Shows when `pending_count <= 3` (client-side threshold). Displays "You're making great progress — ready to plan your next steps?" with a single "Plan next steps" button. On click: calls `planNextSteps()` (always appends) and streams new tasks into the list. Dismissable per session. |

**No `ClarificationChat` component in the Tasks tab.** Clarification happens at project creation
(see `ProjectCreationFlow` below). By the time the user reaches the Tasks tab, the project already
has tasks.

**No `TaskGenerationPanel` component.** There is no manual generation control at rest. The task
list is always populated automatically; the only user-facing generation action is the `PlanNextStepsNudge`.

### Route and page integration

**Route**: No new top-level page — tasks live in a new tab on `ProjectDetailPage.tsx`.

Add a **"Tasks"** tab alongside the existing tabs. The tab shows a task count badge.

```tsx
// In ProjectDetailPage.tsx
<Tab label="Tasks" value="tasks" icon={<ChecklistIcon />} />

// Tab panel — no generation controls at rest
<TabPanel value="tasks">
  <PlanNextStepsNudge projectId={id} />  {/* only visible when pending_count <= 3 */}
  <QuickAddTaskInput projectId={id} />   {/* single-line inline add */}
  <TaskList tasks={tasks} ... />
</TabPanel>
```

`QuickAddTaskInput` is a small inline component (text field + Enter-to-submit) for adding tasks
manually. Task type defaults to `action`. This is for tasks the user wants to capture that the
agent didn't generate — not a substitute for generation.

**Project creation flow** (`ProjectCreationDialog.tsx` or `NewProjectPage.tsx`):

`POST /projects` always succeeds. The dialog saves, closes, and navigates to the project page.
No clarification UI in the creation dialog. If the description is thin, the user will see the
Workflow tab show the "Clarify Description" step in `awaiting_input` state — the same workflow UI
they would use for any paused step. No new UI surface is needed.

### Drag-to-reorder

Use `@dnd-kit/sortable` (already in the MUI ecosystem, lighter than react-beautiful-dnd):

- Reorder is optimistic: update local state immediately, call `reorderTasks` in background.
- On error: revert to server state by invalidating the query.

---

## Task Generation Lifecycle

### On project creation

`POST /projects` always creates the project immediately and enqueues the default workflow run. The
client receives a created project and navigates to the project page. No polymorphic response, no
blocking on description quality.

The workflow then runs:

```
Step 0 — Clarify Description
  description sufficient?
    yes → step completes immediately, workflow advances
    no  → step sets awaiting_input, emits questions; user answers via Workflow tab
          answers appended to project.description; step marked complete; workflow resumes

Step 1 — Evaluate product idea       (PM agent)
Step 2 — Evaluate Engineering arch   (Engineering Lead agent)
Step 3 — Break Down Into Tasks       (task_planner → persists task list)
```

By the time the user finishes reading the project page, tasks are typically already populated.
If clarification is needed, the Workflow tab shows the questions. Answering them is the only
user action between project creation and seeing a task list.

### Ongoing: low-task nudge

`PlanNextStepsNudge` polls `GET /projects/:id/tasks/status` on mount and after each task
completion. When `pending_count ≤ 3`, the nudge appears. The user clicks "Plan next steps" →
`POST /tasks/generate` fires (always append), and new tasks stream into the list. Dismissable;
dismissal stored in session state.

### No manual generation path, no replace mode

There is no "Generate Tasks" button and no replace mode. Generation always appends. On initial
creation there are no prior tasks, so append is a fresh generation. On subsequent nudges, new
tasks are added alongside existing ones — the user's completed and in-progress tasks are never
disturbed.

---

## Testing Plan

### Backend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | `generate-task-list` returns seq of task maps from LLM JSON | Mock LLM response, assert count + types |
| Unit | `assess-description` returns `{:ready false :questions [...]}` for thin descriptions | Mock LLM, assert return shape |
| Unit | `clarify-description-step!` sets step status to `awaiting_input` when `ready: false` | Mock planner, assert step_run update |
| Unit | `bulk-reorder!` is transactional (all or nothing) | Embedded-postgres |
| Integration | Tasks CRUD endpoints + auth isolation | Ring mock + embedded-postgres |
| Integration | `POST /generate` streams tokens then fires `tasks_generated` event with persisted IDs | Embedded-postgres + mock planner |
| Integration | Workflow executor dispatches `task_planner` step type correctly | Mock planner, assert step_run output |
| Integration | Default workflow seeds 4 steps: clarify → pm → engineering_lead → task_planner | Seed + query |

### Frontend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | `TaskTypeChip` renders correct icon per well-known type; unknown label renders plain chip | Vitest + Testing Library |
| Unit | `TaskItem` checkbox toggles `pending` → `completed` | Vitest, synthetic click |
| Unit | `TaskList` drag-drop reorder calls `onReorder` with full new position sequence | Vitest, dnd-kit test utils |
| Unit | `PlanNextStepsNudge` shows when `pending_count <= 3`, hidden when `pending_count > 3` | Vitest, prop variations |
| Integration | `PlanNextStepsNudge` calls `planNextSteps` on confirm; streams tasks into list on `done` event | MSW streaming mock |
| Integration | `PlanNextStepsNudge` dismissal hides nudge for session without refetching | Vitest, local state check |
| Integration | Optimistic drag-drop reorder reverts to server state on API error | MSW 500 response |
| Integration | Tasks tab shows task count badge from `getTasks` result | MSW, count from fixture |
| Integration | Project creation navigates to project page immediately; no clarification UI in dialog | MSW, thin-description fixture |

---

## Build Sequence

1. **Migration 8** — `project_task_generation_runs` + `project_tasks` tables + `updated_at` trigger; add `output_kind TEXT NOT NULL DEFAULT 'text'` column to `workflow_steps`
2. **Malli schemas** — `models/tasks.cljc` (`task_type` as `[:string]`, status as `[:enum "pending" "in_progress" "completed"]`)
3. **Persistence layer** — `persistence/tasks.clj` (CRUD + `bulk-reorder!` + `list-generation-runs` + `get-task-status`)
4. **Task planner LLM** — `tasks/planner.clj` (`assess-description` + `generate-task-list` — pure return-value functions, no `db` or `output-stream` in protocol)
5. **API orchestration** — `api/tasks.clj` (`clarify-description-step!` + `run-task-generation!` + CRUD + reorder + `get-task-status`)
6. **HTTP routes** — `http_api/tasks.clj` (`GET /tasks`, CRUD, `PUT /tasks/reorder`, `GET /tasks/status`, `POST /tasks/generate`) + wire into `kaleidoscope.clj`
7. **Seed `task_planner` agent** in `api/agents.clj` default seeding
8. **Extend workflow executor** — add `:clarify` and `:tasks` defmethods to `execute-step-by-kind!`; add `respond` path that appends answers to `project.description` and resumes workflow
9. **Update default workflow seed** — 4-step workflow (clarify → pm → engineering_lead → task_planner); wire default workflow to fire automatically on `create-project!`
10. **Frontend: types + API client** — `src/types/tasks.ts`, `src/api/tasks.ts` (`planNextSteps` + `getTaskStatus` returning raw counts)
11. **Frontend: `TaskTypeChip` + `TaskItem` + `TaskList`** — base display + drag-drop
12. **Frontend: `PlanNextStepsNudge`** — polls `getTaskStatus`, shows when `pending_count <= 3`, calls `planNextSteps` on confirm
13. **Frontend: wire into `ProjectDetailPage`** — Tasks tab with count badge, `QuickAddTaskInput`, `PlanNextStepsNudge`

---

## Open Design Issues

The following UX/mental-model problems have been identified but are not yet resolved in this plan.
They should be addressed before or during implementation.

| Issue | Problem | Recommended direction |
|-------|---------|----------------------|
| Two chat interfaces | The Conversation tab (Coach/PM/Engineering Lead) and the project-creation clarification flow are both AI chats, but their purposes are different. Users may try to clarify their project in the Conversation tab and wonder why it doesn't update their task list. | Clarification at creation time is addressed in this plan. The remaining gap: when a user has a meaningful exchange in the Conversation tab that changes the project scope, there is no path to refresh tasks from that context. Consider surfacing the `PlanNextStepsNudge` after agent conversations that produce significant output. |
| No project lifecycle guidance | After this feature the project page has 6+ tabs with no suggested starting point. Users don't know what to do first. | Design a project lifecycle view — Describe → Evaluate → Plan → Execute — that shows the user where they are and what's next, rather than presenting all features as equal parallel tabs. This is a larger UX change that spans multiple features. |
