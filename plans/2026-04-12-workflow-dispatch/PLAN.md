# Implementation Plan: Workflow Dispatch

## Overview

Users define reusable **Workflows** — ordered lists of plain-English steps. When a project is
created, the app classifies it against all live workflows and recommends the best match. The user
accepts or overrides, then executes step-by-step (manual) or hands off to the agent (autonomous).

Users can also **skip** any step or fire a **custom ad-hoc action** against any agent at any
time. Custom actions are recorded in the run history and trigger a re-classification to suggest
the most relevant next workflow.

---

## Database Schema

### Migration 5 — workflows & steps

```sql
CREATE TABLE workflows (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT NOT NULL,
  name        TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'draft',  -- draft | live | archived
  is_default  BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMPTZ DEFAULT now(),
  updated_at  TIMESTAMPTZ DEFAULT now()
);

CREATE TABLE workflow_steps (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_id         UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
  position            INT NOT NULL DEFAULT 0,
  name                TEXT NOT NULL,
  description         TEXT NOT NULL,  -- plain-English instruction, fed verbatim to the agent
  created_at          TIMESTAMPTZ DEFAULT now(),
  updated_at          TIMESTAMPTZ DEFAULT now()
);
```

**Seeded workflow** — "Feature Development" (`is_default=true`, `status='live'`):

| position | name | description |
|----------|------|-------------|
| 0 | Evaluate product idea | Evaluate product idea |
| 1 | Evaluate Engineering architecture | Evaluate Engineering architecture. If the architecture score is below a 5, ask the Engineering Architect to suggest ways to implement the feature and, once those recommendations are added to the document, re-score. |

### Migration 6 — workflow runs & step runs

```sql
-- One run per project execution attempt; a project may have multiple runs
CREATE TABLE project_workflow_runs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  workflow_id  UUID REFERENCES workflows(id),  -- nullable: null = free-form (no workflow selected)
  status       TEXT NOT NULL DEFAULT 'pending',
    -- pending | in_progress | awaiting_input | completed | failed
  current_step INT NOT NULL DEFAULT 0,
  mode         TEXT NOT NULL DEFAULT 'manual',  -- manual | autonomous
  started_at   TIMESTAMPTZ,
  completed_at TIMESTAMPTZ,
  created_at   TIMESTAMPTZ DEFAULT now()
);

-- One row per step executed (workflow step or custom)
CREATE TABLE project_workflow_step_runs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_run_id UUID NOT NULL REFERENCES project_workflow_runs(id) ON DELETE CASCADE,
  step_id         UUID REFERENCES workflow_steps(id),  -- null for custom steps
  position        INT NOT NULL,               -- preserves insertion order in the run
  name            TEXT NOT NULL,
  description     TEXT NOT NULL,             -- original step description or custom prompt
  agent_type      TEXT NOT NULL DEFAULT 'coach',  -- coach | pm | engineering_lead
  is_custom       BOOLEAN NOT NULL DEFAULT false, -- true = user-injected, not from workflow
  status          TEXT NOT NULL DEFAULT 'pending',
    -- pending | running | completed | skipped | failed
  output          TEXT,                      -- full agent text on completion
  started_at      TIMESTAMPTZ,
  completed_at    TIMESTAMPTZ
);
```

---

## API Contracts

All routes gated by `auth/require-*-writer`.

### Workflows CRUD

| Method | Path | Body / Notes |
|--------|------|--------------|
| `GET` | `/workflows` | Returns user's workflows + system defaults |
| `POST` | `/workflows` | `{name, description, steps: [{name, description, position}]}` |
| `GET` | `/workflows/:id` | Workflow + ordered steps |
| `PUT` | `/workflows/:id` | `{name?, description?, status?, steps?}` — steps list is full-replace |
| `DELETE` | `/workflows/:id` | 409 if `is_default=true` |

### Workflow Classification

| Method | Path | Notes |
|--------|------|-------|
| `POST` | `/projects/:id/workflow-recommendation` | Ranks all `status='live'` workflows against the project's title + description. Returns `[{workflow_id, name, confidence, rationale}]` descending. |

Called automatically on project creation (fire-and-forget; result stored on the project).
Can be re-called manually to refresh.

### Project Workflow Runs

| Method | Path | Body / Notes |
|--------|------|--------------|
| `GET` | `/projects/:id/workflow-runs` | All runs for project, newest first |
| `POST` | `/projects/:id/workflow-runs` | `{workflow_id?, mode}` — starts a run; if `workflow_id` is null, creates an empty run for free-form custom steps |
| `GET` | `/projects/:id/workflow-runs/:run-id` | Run + all step_runs in order |
| `PUT` | `/projects/:id/workflow-runs/:run-id` | `{mode}` — switch manual ↔ autonomous |

### Step Execution

| Method | Path | Body / Notes |
|--------|------|--------------|
| `POST` | `/projects/:id/workflow-runs/:run-id/advance` | Manual mode: execute the current pending workflow step. Returns updated run via SSE stream. |
| `POST` | `/projects/:id/workflow-runs/:run-id/steps/:step-run-id/skip` | Mark step as `skipped`, advance `current_step`. Returns updated run. |
| `POST` | `/projects/:id/workflow-runs/:run-id/custom-step` | `{name, description, agent_type}` — insert an ad-hoc step at current position, execute it immediately. On completion, re-runs classification and returns `{step_run, recommendation: [{workflow_id, name, confidence, rationale}]}`. |
| `GET` | `/projects/:id/workflow-runs/:run-id/stream` | SSE stream for live step output. Events: `{event: "token", data: "..."}`, `{event: "step_complete", data: {step_run}}`, `{event: "done"}`. |

**Run response shape:**
```json
{
  "id": "run-uuid",
  "projectId": "proj-uuid",
  "workflowId": "wf-uuid",
  "workflowName": "Feature Development",
  "status": "in_progress",
  "mode": "manual",
  "currentStep": 1,
  "steps": [
    {
      "id": "step-run-uuid-1",
      "stepId": "wf-step-uuid-1",
      "position": 0,
      "name": "Evaluate product idea",
      "description": "Evaluate product idea",
      "agentType": "pm",
      "isCustom": false,
      "status": "completed",
      "output": "Score: 7.2 — ...",
      "startedAt": "...",
      "completedAt": "..."
    },
    {
      "id": "step-run-uuid-2",
      "stepId": null,
      "position": 1,
      "name": "Check competitor landscape",
      "description": "List the top 5 competitors for this idea",
      "agentType": "coach",
      "isCustom": true,
      "status": "completed",
      "output": "1. Notion...",
      "startedAt": "...",
      "completedAt": "..."
    }
  ]
}
```

---

## Backend Implementation

### Namespace layout

```
src/kaleidoscope/
  models/
    workflows.cljc          Malli schemas for Workflow, WorkflowStep, WorkflowRun, StepRun
  persistence/
    workflows.clj           HoneySQL queries: CRUD + run/step-run state transitions
  workflows/
    classifier.clj          LLM call: ranks live workflows against project → [{:workflow-id :confidence :rationale}]
    executor.clj            Step engine: dispatch step to agent, stream via SSE, update state
  api/
    workflows.clj           Orchestration: create-run!, advance-step!, skip-step!, run-custom-step!
  http_api/
    workflows.clj           Reitit routes + Ring handlers for /workflows and /projects/:id/workflow-runs
```

### Classifier (`workflows/classifier.clj`)

Builds a prompt containing all `status='live'` workflow names + descriptions. Sends the project
title + description as context. Asks Claude to return a JSON array ranked by confidence (0–1).
Called on project creation (async, result cached on the project row as `workflow_recommendation
JSONB`) and re-called after each custom step completes.

### Executor (`workflows/executor.clj`)

```clojure
(defprotocol IExecutor
  (execute-step! [this db project step-description agent-type output-stream]
    "Streams agent tokens to output-stream. Returns full output string."))
```

Each step execution:
1. Inserts/updates step_run to `status=running`, `started_at=now()`
2. Builds system prompt from agent persona (reuses `scoring/agents.clj` personas) + project context
3. Calls Claude streaming API; each token is written as `data: {"event":"token","data":"<tok>"}` via `write-sse-event!`
4. On completion: sets `status=completed`, `output=<full-text>`, `completed_at=now()`
5. Writes `data: {"event":"step_complete","data":{...step_run}}` then `data: {"event":"done"}`

For the "re-score if below 5" step: the step description is passed verbatim; the executor injects
the project's current score JSON into the agent context so the LLM can read scores and apply the
conditional logic itself — no hard-coded branching.

**Skip:** Sets `status=skipped`, increments `current_step` on the run. Autonomous mode immediately
calls `advance-step!` on the next step. Manual mode stops and waits.

**Custom step:** Inserts a new step_run with `is_custom=true` at the next available position.
Executes immediately (regardless of mode). On completion, calls the classifier and returns the
recommendation alongside the step_run in the response body.

**Autonomous mode:** After each step completes (including skip), executor checks if `current_step`
< total steps; if so, calls `advance-step!` on the next step without waiting for the frontend.

---

## Frontend Implementation

### New types (`src/types/workflow.ts`)

```ts
export type WorkflowStatus = 'draft' | 'live' | 'archived';
export type RunStatus = 'pending' | 'in_progress' | 'awaiting_input' | 'completed' | 'failed';
export type StepRunStatus = 'pending' | 'running' | 'completed' | 'skipped' | 'failed';
export type RunMode = 'manual' | 'autonomous';

export interface WorkflowStep {
  id: string;
  workflowId: string;
  position: number;
  name: string;
  description: string;
}

export interface Workflow {
  id: string;
  name: string;
  description?: string;
  status: WorkflowStatus;
  isDefault: boolean;
  steps: WorkflowStep[];
}

export interface StepRun {
  id: string;
  stepId: string | null;   // null = custom step
  position: number;
  name: string;
  description: string;
  agentType: string;
  isCustom: boolean;
  status: StepRunStatus;
  output?: string;
  startedAt?: string;
  completedAt?: string;
}

export interface WorkflowRun {
  id: string;
  projectId: string;
  workflowId: string | null;
  workflowName: string | null;
  status: RunStatus;
  mode: RunMode;
  currentStep: number;
  steps: StepRun[];
  startedAt?: string;
  completedAt?: string;
  createdAt: string;
}

export interface WorkflowRecommendation {
  workflowId: string;
  name: string;
  confidence: number;
  rationale: string;
}
```

### New API client (`src/api/workflows.ts`)

```ts
// Workflow CRUD
getWorkflows(token?)
getWorkflow(id, token?)
createWorkflow(body, token?)
updateWorkflow(id, body, token?)
deleteWorkflow(id, token?)

// Classification
getWorkflowRecommendation(projectId, token?)   → WorkflowRecommendation[]

// Runs
getWorkflowRuns(projectId, token?)             → WorkflowRun[]
startWorkflowRun(projectId, body, token?)      → WorkflowRun   // {workflow_id?, mode}
getWorkflowRun(projectId, runId, token?)       → WorkflowRun
updateRunMode(projectId, runId, body, token?)  → WorkflowRun   // {mode}

// Step actions
advanceRun(projectId, runId, token?)           → void (streams via /stream)
skipStep(projectId, runId, stepRunId, token?)  → WorkflowRun
runCustomStep(projectId, runId, body, token?)  → {stepRun, recommendation: WorkflowRecommendation[]}

// SSE stream — mirrors streamMessage() in projects.ts
streamWorkflowRun(projectId, runId, token?)    → AsyncGenerator<{event, data}>
```

React Query keys:
- `['workflows']`, `['workflows', id]`
- `['projects', id, 'workflow-recommendation']`
- `['projects', id, 'workflow-runs']`, `['projects', id, 'workflow-runs', runId]`

### New pages

| Page | Route | Purpose |
|------|-------|---------|
| `WorkflowsPage` | `/workflows` | List all workflows; create new |
| `WorkflowEditorPage` | `/workflows/new`, `/workflows/:id` | Edit name/description/status; manage ordered steps |

Routes added to `App.tsx`:
```ts
{ path: '/workflows',     element: <WorkflowsPage /> },
{ path: '/workflows/new', element: <WorkflowEditorPage /> },
{ path: '/workflows/:id', element: <WorkflowEditorPage /> },
```

### New components (`src/components/workflows/`)

| Component | Purpose |
|-----------|---------|
| `WorkflowCard` | Name, step count, status chip (draft/live/archived); Edit and Archive buttons |
| `WorkflowStepList` | Ordered, editable step list in the editor; add/remove/reorder |
| `WorkflowRecommendationBanner` | Shown on project page: "Suggested: Feature Development (87% match) — [Start] [Dismiss]". Appears after project creation; re-appears after a custom step completes with a new recommendation. |
| `WorkflowRunPanel` | Main workflow area on the project detail page. Contains the stepper and action bar. |
| `WorkflowStepper` | Shows each step_run as a row: icon (pending / spinner / check / skip / fail), name, output accordion. Skipped steps render greyed out. Custom steps render with a "Custom" badge. |
| `WorkflowActionBar` | Contextual button strip below the stepper: **Advance** (manual mode, next workflow step), **Skip** (skip current step), **Custom Action** (opens dialog), **Mode toggle** (manual ↔ autonomous) |
| `CustomStepDialog` | Modal: step name, free-text description, agent selector (coach / pm / engineering_lead). On submit calls `runCustomStep`. On completion shows the new recommendation inline. |

### Changes to `ProjectDetailPage.tsx`

Add a **Workflow** tab alongside the existing tabs (Scoring, Notes, etc.):

- On load: fetch `getWorkflowRuns(projectId)`. If none, fetch `getWorkflowRecommendation(projectId)` and show `WorkflowRecommendationBanner`.
- If an active run exists: render `WorkflowRunPanel`. Open the SSE stream via `streamWorkflowRun` and append tokens to the currently-running step_run's output in local state. Invalidate `['projects', id, 'workflow-runs', runId]` on `step_complete` events.
- If the run is `completed` or `failed`: show a summary and offer "Start new run".

---

## Testing Plan

### Backend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | Classifier returns array sorted by confidence desc | Mock LLM, assert ordering |
| Unit | Executor state machine: pending→running→completed | In-memory, no DB |
| Unit | Skip sets status=skipped, increments current_step | Pure logic |
| Unit | Custom step sets is_custom=true and triggers re-classification | Mock classifier |
| Integration | Workflow CRUD + step reorder | Ring mock + embedded-postgres |
| Integration | Start run → advance all steps → status=completed | Embedded-postgres, mock executor |
| Integration | Skip a step, then advance the next | Embedded-postgres |
| Integration | Custom step inserted mid-run; run history shows it | Embedded-postgres |
| Integration | Seeded "Feature Development" workflow present on first use | Seed + query |

### Frontend

| Layer | Scope | Approach |
|-------|-------|----------|
| Unit | `WorkflowStepper` renders correct icon per StepRunStatus; custom badge on is_custom | Vitest + Testing Library |
| Unit | `WorkflowStepList` add/remove/reorder steps | Vitest, synthetic events |
| Integration | `WorkflowsPage` list, create, archive | MSW |
| Integration | `WorkflowEditorPage` add steps, change status to live, save | MSW |
| Integration | `WorkflowRunPanel` — SSE tokens appear in step output; step_complete event closes accordion | MSW streaming mock |
| Integration | Skip step → step greyed, next step actionable | MSW |
| Integration | Custom step dialog → submit → recommendation banner updates | MSW |
| Integration | `WorkflowRecommendationBanner` accept → creates run | MSW |

---

## Build Sequence

1. DB migrations 5 + 6 + seed data
2. Malli schemas (`models/workflows.cljc`)
3. Persistence layer (`persistence/workflows.clj`)
4. Classifier — LLM ranking call
5. Executor — step engine (mock LLM for initial dev)
6. HTTP routes + handlers (testable end-to-end with mock executor)
7. Workflow CRUD pages (`WorkflowsPage`, `WorkflowEditorPage`)
8. Workflow types + API client in `kaleidoscope-ui`
9. `WorkflowRecommendationBanner` + auto-recommendation after project creation
10. `WorkflowRunPanel` + SSE live updates
11. Skip step + custom step dialog + post-custom re-classification
12. Autonomous mode (auto-advance after each step completes)
