# Idea Strengthening Loop

**Date**: 2026-04-15
**Goal**: Enable users to describe an idea at any stage of clarity, have the system automatically strengthen it via an agent feedback loop, and then execute on it.

**Design principle**: Agents as a feedback loop. Every transition should answer "did the score improve, and in which dimensions?" The score trajectory — visible round by round — is what makes the feedback loop legible.

---

## Vision

```
Idea → [Score] → feedback → [Improve brief] → [Score] → ... → [Execute]
         ↑___________________________|
```

Users describe an idea in any level of detail (early-stage or well-formed). The system:
1. Selects an appropriate workflow
2. Runs the advisor → judge → refine loop iteratively
3. Shows score improvement round-by-round until the user is satisfied
4. Executes the idea (code generation, research, calendar events)

---

## Current State

### What's already working (the foundation)

- **Score → Judge → Refine → Re-score loop** — `AutonomousTeamReview` workflow runs parallel PM/Eng scoring, feeds a Judge step that reads trajectory + delta across rounds, and emits `refine | clarify | proceed`.
- **Brief versioning** — the project description evolves as advisors refine it (stored in `project_briefs`). Each round scores an improved version.
- **Task generation** — when Judge says `proceed`, a task planner generates structured tasks.
- **Workflow recommendation** — `/projects/{id}/workflow-recommendation` endpoint exists to classify the best workflow for a project.
- **Clarify/respond** — backend supports pausing the workflow for user input (`clarify` step type + `/respond` endpoint).

The infrastructure is there. What's missing is **visibility**, **user control**, and **execution depth**.

---

## The 4 Critical Gaps

### Gap 1 — The loop is invisible

The UI (`WorkflowRunPanel`) shows raw step outputs, not the narrative of improvement. A user cannot see:
- "Round 1: avg score 5.2 → Round 2: avg score 7.1 → Round 3: 8.4, judge said proceed"
- What specifically changed in the brief between rounds
- Which dimension improved and why

**Fix**: Replace the step-list view with a **rounds timeline** — each round shows a score card (before/after), the advisor's key changes to the brief, and the judge's reasoning.

Data already available: `project_workflow_rounds`, `project_judge_records`, `project_briefs`, `project_score_runs`.

---

### Gap 2 — User has no steering wheel

The Judge AI decides when to proceed. The user cannot say "keep going until score > 8," "I'm satisfied, proceed to execution," or "pause here, I want to add context."

The backend already supports `clarify` (pauses workflow, waits for `/respond`). The UI surface is missing.

**Fix**: After each round completes, show an inline decision point:
- **Continue** — keep strengthening (optionally set a target score)
- **I'm satisfied** — skip to execution
- **Add context** — user clarification input → feeds into the next brief

---

### Gap 3 — Workflow selection is manual and hidden

Users must navigate to `/workflows`, pick one, and run it. The system should recommend based on project state:
- Early-stage idea → Autonomous Team Review
- Well-formed PRD → Jump straight to task generation

**Fix**: Wire `/workflow-recommendation` into the project detail page. "Strengthen this idea" button → shows recommended workflow with a one-sentence rationale → one-click to start.

---

### Gap 4 — Execution stops at task lists

After `proceed`, the user gets a task list. Nothing executes them. The vision requires:
- **Research**: web search + summarize → feed results back into the brief
- **Code generation**: generate files or diffs from task + workspace root
- **Scheduling**: create calendar events from tasks with `estimated_minutes`

These require new agent types with tools (not just system prompts). Biggest engineering lift — should come last.

---

## Architectural Decisions

### AD-1: Rounds data shape — new `/rounds` endpoint vs. enriching the existing run endpoint

The existing `GET /workflow-runs/:id` returns a flat step-list. We need a rounds-first view: round → [score before/after, judge record, brief delta]. Enriching the existing response would nest new shapes inside the current step-list, breaking existing consumers.

**Decision: New `GET /projects/:id/workflow-runs/:run-id/rounds` endpoint.**

Returns:
```json
[{
  "round_number": 1,
  "status": "completed",
  "started_at": "...",
  "completed_at": "...",
  "brief": {"version": 2, "content": "..."},
  "judge": {
    "decision": "refine",
    "summary": "...",
    "rationale": "...",
    "delta_table": {"pm / Clarity": {"delta": 1.2, "regressed": false}},
    "trajectory": {"pm / Clarity": [5.2, 6.4]}
  },
  "scores": {
    "before": {"pm": 5.2, "engineering_lead": 5.8},
    "after":  {"pm": 6.4, "engineering_lead": 6.7}
  }
}]
```

Joins: `workflow_rounds` → `workflow_judge_records` (by `round_id`) → `project_briefs` (by `workflow_round_id`) → `project_score_runs` (via `project_workflow_step_runs.score_run_id`).

---

### AD-2: Live updates — `round_complete` SSE event vs. polling

The existing `/advance` SSE stream already flows to the frontend. Polling `/rounds` independently creates a second connection and imprecise timing.

**Decision: Emit a `round_complete` SSE event from the loop.**

In `run-loop-workflow!` (`api/workflows.clj`), after a round is fully written to the DB, emit:
```
event: round_complete
data: {"round_number": 2, "decision": "refine"}
```

The frontend re-fetches `/rounds` on this event. One connection, tight timing, no polling.

---

### AD-3: User checkpoint — always visible vs. only on `proceed`

Option A: Show the action bar only when the Judge says `proceed` (AI stays in control until it decides to hand off). Option B: Show it after every round (user can cut short at any time).

**Decision: Always show the action bar after each completed round.**

The Judge is an advisor, not a gatekeeper. "I'm satisfied" must be the user's choice to make, not the AI's. This is consistent with the design principle: *user controls when the loop stops*.

---

### AD-4: Brief diff display — live diff vs. judge-computed summary

The judge already writes `summary`, `rationale`, and `delta_table` into `workflow_judge_records`. Computing a text diff on the frontend requires diffing two long strings and displaying a noisy patch.

**Decision: Render the judge's `summary` + `delta_table` rather than a text diff.**

The judge already answers "what changed and why." Use it. Reserve text diffing for an optional "Show full brief changes" expand section if users want it.

---

### AD-5: Force-proceed mechanism — reuse `/skip` vs. new endpoint

To let users short-circuit the loop, we need a way to skip remaining refinement steps and jump to task generation. The existing `/skip` endpoint operates on individual steps, which requires the frontend to know the step topology.

**Decision: New `POST /projects/:id/workflow-runs/:run-id/force-proceed` endpoint.**

The backend finds the current pending step, completes the active round, and transitions directly to the `proceed` path (runs task generation). The frontend sends one call with no step-topology knowledge.

---

### AD-6: Workflow recommendation trigger — on page load vs. button

Calling `POST /projects/:id/workflow-recommendation` on every project page load hits the LLM on every navigation, which is expensive and jarring.

**Decision: Button-triggered ("Strengthen this idea").**

Recommendation runs on explicit user intent. Once fetched, cache the result in local component state for the session (no need to persist it — it's cheap to re-fetch).

---

### AD-7: Phase 4 execution artifacts — on project vs. on tasks

Research summaries, generated diffs, and calendar event links are outputs of specific tasks. Hanging them off the project makes the data model flat and unqueryable by task.

**Decision: Artifacts live on tasks via a `task_artifacts` table.**

Schema: `(id, task_id, artifact_type, content, url, created_at)`. This keeps the task as the atomic unit of work and its output co-located. The project page can aggregate them, but the source of truth is per-task.

---

## Phased Roadmap

### Phase 1 — Make the loop visible (2–3 days)

Build a **rounds timeline** view in `WorkflowRunPanel`. Each round = one card showing score before/after per dimension, judge summary, and decision.

No schema changes needed. All data is already persisted.

**Deliverable**: User can watch the score improve round-by-round and understand what changed.

#### Implementation steps

**Step 1.1 — Backend: rounds persistence query**
- File: `src/kaleidoscope/persistence/workflows.clj`
- Add `get-workflow-run-rounds` query: joins `workflow_rounds`, `workflow_judge_records`, `project_workflow_step_runs` (for score_run_id), `project_score_runs`, and `project_briefs` (by `workflow_round_id`).
- Shape the result into the round map defined in AD-1.

**Step 1.2 — Backend: `run-rounds` API function**
- File: `src/kaleidoscope/api/workflows.clj`
- Add `get-run-rounds [db run-id]` → calls the persistence query, formats the before/after score pairs (score of the previous round's end = this round's `before`).

**Step 1.3 — Backend: `/rounds` HTTP route**
- File: `src/kaleidoscope/http_api/workflows.clj`
- Add `GET /projects/:project-id/workflow-runs/:run-id/rounds` → calls `get-run-rounds`, returns JSON array.

**Step 1.4 — Backend: `round_complete` SSE event**
- File: `src/kaleidoscope/api/workflows.clj`, in `run-loop-workflow!`
- After writing the completed round to the DB, write `event: round_complete\ndata: {"round_number": N, "decision": "refine"|"proceed"}` to `output-stream`.

**Step 1.5 — Frontend: `RoundsTimeline` component**
- File: `kaleidoscope-ui`: new `RoundsTimeline.cljs`
- Fetches `GET /rounds` on mount.
- Subscribes to the SSE stream; on `round_complete` event, re-fetches `/rounds`.
- Renders one `RoundCard` per round.

**Step 1.6 — Frontend: `RoundCard` component**
- File: `kaleidoscope-ui`: new `RoundCard.cljs`
- Shows: round number, score-before vs. score-after per dimension (two numbers + colored delta badge), judge summary + decision chip ("Refining" / "Proceeding"), brief version used.

**Step 1.7 — Frontend: swap `WorkflowRunPanel` for loop workflows**
- File: `kaleidoscope-ui`: `WorkflowRunPanel.cljs`
- When `mode = "autonomous"` and the workflow has `loop-until`, render `RoundsTimeline` instead of the step list.

---

### Phase 2 — Give user the steering wheel (1–2 days)

After each completed round card, surface an inline action bar.

**Deliverable**: User controls when the loop stops.

#### Implementation steps

**Step 2.1 — Backend: `force-proceed` endpoint**
- File: `src/kaleidoscope/http_api/workflows.clj`, `src/kaleidoscope/api/workflows.clj`
- `POST /projects/:id/workflow-runs/:run-id/force-proceed`
- API function: complete the current round, mark any pending refinement steps as skipped, transition the run to the `proceed` path (triggering task generation via the existing sequential tail logic).

**Step 2.2 — Backend: `target_score` in run config**
- File: `src/kaleidoscope/api/workflows.clj`, in `run-loop-workflow!`
- Read `config.target_score` from the run. After each round, compute avg score across all dimensions. If avg ≥ target, call the `force-proceed` logic instead of continuing.
- `PUT /workflow-runs/:id` already exists to update config — no new route needed.

**Step 2.3 — Frontend: `RoundActionBar` component**
- File: `kaleidoscope-ui`: new `RoundActionBar.cljs`
- Rendered at the bottom of each completed `RoundCard` (or only the latest one).
- Three actions:
  - **Keep strengthening** → calls `POST /advance`
  - **Add context** → reveals a text-area → on submit calls `POST /steps/:step-id/respond`
  - **I'm satisfied → Generate tasks** → calls `POST /force-proceed`
- Disable all three while a round is in progress (round status = `in_progress`).

**Step 2.4 — Frontend: target score input**
- File: `kaleidoscope-ui`: `WorkflowStartModal.cljs` (or equivalent run-creation dialog)
- Optional numeric field "Run until score ≥ ___" (0–10, step 0.5). Defaults empty (judge-controlled).
- On run creation, include `target_score` in the `POST /workflow-runs` body config.

---

### Phase 3 — Surface workflow recommendation (1 day)

Replace manual workflow browsing with a guided entry point on `ProjectDetailPage`.

**Deliverable**: Zero-friction path from project to running the loop.

#### Implementation steps

**Step 3.1 — Frontend: "Strengthen this idea" button on `ProjectDetailPage`**
- File: `kaleidoscope-ui`: `ProjectDetailPage.cljs`
- Button calls `POST /projects/:id/workflow-recommendation`.
- While loading: show a spinner with "Analyzing your project…"
- On response: open `WorkflowRecommendationModal`.

**Step 3.2 — Frontend: `WorkflowRecommendationModal` component**
- File: `kaleidoscope-ui`: new `WorkflowRecommendationModal.cljs`
- Shows: workflow name, one-sentence rationale, confidence badge.
- Two CTAs: **Start** (calls `POST /workflow-runs` with the recommended `workflow_id`) and **Browse other workflows** (navigates to the existing `/workflows` list).
- Cache the recommendation result in local state for the session — no re-fetch on re-open.

---

### Phase 4 — Richer execution (1–2 weeks)

Add new step types to the workflow engine so tasks actually get done, not just planned.

**Deliverable**: The system can execute work, not just describe it.

#### Implementation steps

**Step 4.1 — DB migration: `task_artifacts` table**
- File: new migration in `resources/migrations/`
- Schema: `id UUID PK, task_id UUID FK(project_tasks.id), artifact_type TEXT, content TEXT, url TEXT, created_at TIMESTAMPTZ`

**Step 4.2 — Backend: `research` step type**
- File: `src/kaleidoscope/workflows/llm_executor.clj`, new `src/kaleidoscope/workflows/tools/web_search.clj`
- New branch in `execute-step!` for `agent-type: "researcher"`.
- Agent reads task description + current brief → calls web search API (Brave/Tavily/Serper) → LLM summarizes findings → appends summary to brief (via existing brief-update path) + saves as a task artifact.
- Requires: web search API key in env, `web_search.clj` wrapping the HTTP call.

**Step 4.3 — Backend: `code_generation` step type**
- File: `src/kaleidoscope/workflows/llm_executor.clj`, new `src/kaleidoscope/workflows/tools/file_ops.clj`
- Agent reads task + workspace root path → calls Claude with file-read tools → produces a diff or file scaffold → saves as a task artifact (`artifact_type: "code_patch"`).
- Requires: workspace root stored on the project or supplied per-step (the Engineering Review step already collects `code_context_path` — reuse it).

**Step 4.4 — Backend: `scheduling` step type**
- File: `src/kaleidoscope/workflows/llm_executor.clj`, new `src/kaleidoscope/workflows/tools/calendar.clj`
- Reads tasks with `estimated_minutes` → creates calendar events via Google Calendar API → saves event URLs as task artifacts (`artifact_type: "calendar_event"`).
- Requires: OAuth token stored per-user, Google Calendar API integration.

**Step 4.5 — Frontend: artifact display in task list**
- File: `kaleidoscope-ui`: `TaskCard.cljs` (or equivalent)
- If a task has artifacts, show them inline: research summaries as expandable text blocks, code patches as a monospace diff, calendar events as an external link.

---

## Key Data Seams

| Seam | Backend | Frontend |
|------|---------|----------|
| Round history | `workflow_rounds` + `workflow_judge_records` | `RoundsTimeline` (new) |
| Brief evolution | `project_briefs` (by `workflow_round_id`) | Shown per round in `RoundCard` |
| Score trajectory | `project_score_runs` (joined via step_runs) | Before/after in `RoundCard` |
| User clarification | `POST /steps/:stepId/respond` | "Add context" in `RoundActionBar` |
| Force proceed | `POST /workflow-runs/:id/force-proceed` (new) | "I'm satisfied" in `RoundActionBar` |
| Target score | `workflow_run.config.target_score` | Input in `WorkflowStartModal` |
| Workflow recommendation | `POST /projects/:id/workflow-recommendation` | `WorkflowRecommendationModal` (new) |
| Task artifacts | `task_artifacts` table (new) | `TaskCard` artifact section |

---

## Open Questions

1. Should the user-satisfaction checkpoint always appear on every round card, or only on the latest/active one? (Risk of a stale "I'm satisfied" on an old round.)
2. For research tasks — should web search happen inline during the strengthen loop, or only during execution?
3. What's the right target score UX — a slider, a numeric input, or "good / great / excellent" presets?
4. For code generation: does the workspace root come from the `code_context_path` already collected by the Engineering Review step, or does the user supply it separately per execution run?
