# Score → Judge → Refine Workflow

## Concept

The user describes an idea. A team of advisors reviews it independently, each
from their own angle. The team lead reads all the feedback, fixes what can be fixed
without involving the user, and asks the user when something genuinely requires their
input. Once the team is satisfied — or satisfied enough — task generation begins
automatically.

The user's job is to describe their idea and answer questions when asked. Everything
else happens on their behalf.

---

## What the user experiences

From the user's perspective there are three states:

**Reviewing** — The advisors are working. The user sees a live feed of what each
advisor found, in plain language. No action is required.

**Waiting for your input** — The team lead needs something only the user knows.
A question (or short list of questions) is shown with a text input. The user
answers and the review continues.

**Done** — The team is satisfied. Task generation ran automatically and the task
list is ready. Any gaps the team could not resolve appear as investigation tasks.

There is no round counter, no numeric score, no mention of iterations, no
confirmation button. The system tracks that information internally.

---

## Core loop

```
                    ┌──────────────────────────────┐
                    │  Advisors review the brief    │
                    │  (independently, in parallel) │
                    └──────────────┬───────────────┘
                                   │
                                   ▼
                    ┌──────────────────────────────┐
                    │  Team lead reads all feedback,│
                    │  score trajectory, and deltas │
                    │  then decides what to do next │
                    └──────────────┬───────────────┘
                                   │
           ┌───────────────────────┼───────────────────────┐
           ▼                       ▼                       ▼
    Advisor can fix it       Need user input          Good enough
    autonomously             (pause for answer)       → task generation
           │                       │                   starts immediately
    Advisor updates          User answers
    the brief. Loop          appended to brief.
    repeats.                 Loop repeats.
```

The loop runs until the team lead decides to proceed, or until the iteration limit
is reached. At the limit the team lead must choose between proceeding or asking the
user — no further autonomous refinement is permitted.

---

## The team

Each advisor owns a domain and a fixed set of criteria within it. The team lead
reads all advisor feedback but scores nothing themselves.

| Advisor | Domain | What they look for |
|---|---|---|
| **Product Manager** | Intent | Problem clarity, target user, success metrics, market context, differentiation |
| **Engineering Lead** | Architecture | Module design, APIs, data model, scalability, security, technology choices, implementation risk |
| **Team Lead** | Synthesis | Reads all feedback, decides next action, asks the user when needed |

Any advisor with a defined set of criteria can join the team. The loop does not
change when a new advisor is added.

---

## Scrutiny levels

When a run starts, the user (or a pre-classifier) chooses how much scrutiny the
idea needs:

| Level | When to use | Internal behaviour |
|---|---|---|
| **Quick check** | Experiments, throwaway prototypes | Lower thresholds, max 1 round |
| **Standard review** | Most features | Default thresholds, max 2 rounds |
| **Rigorous review** | High-stakes or hard-to-reverse decisions | Higher thresholds, max 3 rounds |

The numeric thresholds and round limits are implementation details. The user
chooses a level; the system maps it to policy.

---

## What the user sees per advisor

Each advisor's result is shown as a status card, not a score sheet:

```
┌─────────────────────────────────────────────────────┐
│ 🦉 Engineering Lead                    Needs work   │
├─────────────────────────────────────────────────────┤
│ ✓ Module design       Clear                         │
│ ✓ Security            Clear                         │
│ ! Scalability         No data volume estimate.      │
│                       Unclear which paths are hot.  │
│ ! API design          Interfaces between components │
│                       are not specified.            │
└─────────────────────────────────────────────────────┘
```

Statuses: **Clear** / **Needs work** / **Blocked**.

Numeric scores exist in the data but are not shown in the primary UI.

---

## The team lead card

The team lead's output is the most prominent element in the review. It shows what
the team found, what action was taken, and why.

When the action is **proceed**, task generation starts immediately:

```
┌─────────────────────────────────────────────────────┐
│ Team Lead                                           │
├─────────────────────────────────────────────────────┤
│ PM feedback is strong. The scalability gap has been │
│ addressed by the Engineering Lead. Remaining gaps   │
│ (API interface detail) are minor and will appear as │
│ investigation tasks.                                │
│                                                     │
│ Generating task list…                               │
└─────────────────────────────────────────────────────┘
```

When the action is **refine**, the card shows what the advisor is fixing and why.
When the action is **needs your input**, the card shows the questions and an answer
form.

---

## The brief

When the user creates a project they write a description. That description is
preserved exactly as written — it is a fact, not subject to change.

The advisors work from a **brief** — a living document that starts as a copy of the
description. Advisor refinements update the brief. User answers are appended to it.
All scoring is computed against the current brief.

The user sees one thing: their idea, as it currently stands. When an advisor has
added or changed something, it is shown with clear attribution:

> *Engineering Lead added context on scalability*
> *You added context on expected user volume*

A diff view shows exactly what changed. This is not optional — silent autonomous
edits to a document the user owns create distrust.

The brief is versioned internally. Every score record links to the brief version it
evaluated.

---

## Data model

### New table: `workflow_rounds`

```sql
CREATE TABLE workflow_rounds (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_run_id UUID NOT NULL REFERENCES project_workflow_runs(id) ON DELETE CASCADE,
  round_number    INT  NOT NULL,
  status          TEXT NOT NULL DEFAULT 'in_progress', -- in_progress | completed
  started_at      TIMESTAMP NOT NULL DEFAULT now(),
  completed_at    TIMESTAMP,
  UNIQUE (workflow_run_id, round_number)
);
```

The round's outcome is derived by reading the `output_kind: decision` step result
that belongs to this round. It is not denormalised onto the round row.

### New table: `workflow_judge_records`

```sql
CREATE TABLE workflow_judge_records (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  step_run_id    UUID  NOT NULL REFERENCES project_workflow_step_runs(id),
  round_id       UUID  NOT NULL REFERENCES workflow_rounds(id),
  -- Complete input snapshot at decision time
  brief_version  INT   NOT NULL,
  score_snapshot JSONB NOT NULL,  -- all scores + rationale for this round
  trajectory     JSONB NOT NULL,  -- per-dimension scores across all prior rounds
  delta_table    JSONB NOT NULL,  -- per-dimension delta vs previous round + regression flags
  policy         JSONB NOT NULL,  -- thresholds, deadband, max_rounds, current_round
  -- Output
  decision       JSONB NOT NULL,  -- full judge output
  created_at     TIMESTAMP NOT NULL DEFAULT now()
);
```

Every judge decision is fully reconstructable from this record. No external context
is required to understand why a decision was made. This table is append-only.

### Modified table: `project_workflow_step_runs`

```sql
ALTER TABLE project_workflow_step_runs
  ADD COLUMN round_id      UUID REFERENCES workflow_rounds(id),
  ADD COLUMN score_run_id  UUID REFERENCES score_runs(id);
```

`score_run_id` is the single authoritative reference to the score record for score
steps. Score data is not duplicated in the step result output field.

### New table: `project_briefs`

```sql
CREATE TABLE project_briefs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id        UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  version           INT  NOT NULL,
  content           TEXT NOT NULL,
  source            TEXT NOT NULL, -- 'initial' | 'advisor_refinement' | 'user_clarification'
  agent_type        TEXT,          -- set when source = 'advisor_refinement'
  workflow_round_id UUID REFERENCES workflow_rounds(id), -- null for initial version
  created_at        TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, version)
);
```

`workflow_round_id` is a FK, not a bare integer. A brief version from round 2 of
run A is distinct from round 2 of run B.

### Modified table: `project_workflow_runs`

```sql
ALTER TABLE project_workflow_runs
  ADD COLUMN config JSONB NOT NULL DEFAULT '{}';
```

The `config` column carries the full policy block:

```json
{
  "scrutiny": "standard",
  "max_rounds": 2,
  "thresholds": { "pm": 6.5, "engineering_lead": 6.0, "default": 6.0 },
  "deadband": 0.5,
  "timeouts": {
    "score_step_seconds": 60,
    "refine_step_seconds": 120,
    "fan_in_seconds": 90
  }
}
```

`deadband` widens each threshold by ±0.5 to prevent threshold chatter on noisy
measurements near the boundary. `timeouts` caps the wall-clock duration of each
LLM call and of the fan-in wait.

### Modified table: `score_runs`

```sql
ALTER TABLE score_runs
  ADD COLUMN brief_version INT;
```

### Modified table: `workflow_steps`

```sql
ALTER TABLE workflow_steps
  ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'sequential',
  ADD COLUMN loop_until     TEXT;
```

### `output_kind` and `execution_mode` values

`output_kind` describes the **shape** of a step's output. `execution_mode` describes
**how the executor runs the step**. These are independent dimensions.

**`output_kind`:**

| value | output shape |
|---|---|
| `text` | Markdown prose |
| `clarify` | JSON `{ready, reply}` |
| `tasks` | JSON array of task objects |
| `score` | References a `score_run` via `score_run_id` |
| `decision` | JSON routing decision `{action, summary, rationale, …}` |

**`execution_mode`:**

| value | behaviour |
|---|---|
| `sequential` | Runs after the preceding step completes (default) |
| `parallel` | Launches concurrently with other `parallel` steps in the same contiguous group |
| `fan_in` | Waits for all preceding `parallel` steps to reach a terminal state before running |

---

## Workflow definition

```
position 0  output_kind: score     execution_mode: parallel    agent: pm               name: "PM review"
position 1  output_kind: score     execution_mode: parallel    agent: engineering_lead  name: "Engineering review"
position 2  output_kind: decision  execution_mode: fan_in      agent: judge             name: "Team Lead"
            loop_until: proceed
position 3  output_kind: tasks     execution_mode: sequential  agent: task_planner      name: "Generate tasks"
```

`loop_until: proceed` on the judge step makes the loop contract explicit in the
definition. The executor reads this field and either creates a new round or advances
to position 3 — it does not hard-code knowledge about `decision` steps.

---

## Execution model

### Round lifecycle

When a run begins, the executor creates round 1. Each round:

1. **Score (parallel)**: all `execution_mode: parallel` steps launch as concurrent
   futures, each subject to `config.timeouts.score_step_seconds`. Each scores the
   current brief version, writes a `score_run` and dimension rows, then creates a
   step result with `score_run_id` set.

2. **Fan-in**: once all score futures reach a terminal state (completed, failed, or
   timed-out), or once `config.timeouts.fan_in_seconds` elapses, the fan-in step
   runs. A timed-out score step is treated as failed. Partial failure is handled as
   described below.

3. **Pre-judge computation**: before the judge LLM is called, the executor derives:
   - **Trajectory table**: per-dimension scores across all previous rounds, pulled
     from `score_runs` linked to this run
   - **Delta table**: per-dimension difference between this round and the previous
     round; dimensions with a negative delta are flagged as regressions
   - These are computed from existing data — no LLM call required

4. **Judge**: receives the current brief, all score results for this round
   (including failure sentinels), the trajectory table, the delta table, and the
   full policy config including `current_round` and `max_rounds`.
   A `workflow_judge_record` is written capturing the complete input and output.

5. **Routing**: the judge's `action` field is compared against `loop_until`. If
   they match, execution advances to position 3. If not, a new round is created.

### Partial score failure

If a score step fails or times out, its step result is marked `failed`. The judge
still runs and receives a failure sentinel for that advisor:

```json
{ "failed": true, "agent": "engineering_lead", "reason": "timeout" }
```

The judge's system prompt instructs it to treat missing scores as evidence of
uncertainty, not satisfaction. If the failed advisor's domain is relevant to the
decision, prefer `clarify` over `proceed`.

### Judge input

The judge receives a single structured context block:

```json
{
  "brief": "<current brief content>",
  "current_round": 2,
  "max_rounds": 2,
  "thresholds": { "pm": 6.5, "engineering_lead": 6.0 },
  "deadband": 0.5,
  "scores": {
    "pm":               { "overall": 7.1, "dimensions": [...] },
    "engineering_lead": { "overall": 4.3, "dimensions": [...] }
  },
  "trajectory": {
    "pm / Problem clarity":       [5.2, 6.8, 7.1],
    "eng / Scalability":          [3.1, 3.4, 3.2],
    "eng / API design":           [4.0, 4.5, 4.3]
  },
  "deltas": {
    "pm / Problem clarity":       { "delta": +0.3,  "regressed": false },
    "eng / Scalability":          { "delta": -0.2,  "regressed": true  },
    "eng / API design":           { "delta": -0.2,  "regressed": true  }
  }
}
```

The trajectory and delta table allow the judge to reason about whether the loop is
converging, saturating, or regressing — without the executor needing to encode any
of that logic as rules.

### Judge system prompt instructions

The judge's system prompt instructs it to apply the following reasoning to the data
it receives. These are guidelines encoded in the prompt, not hard rules in the
executor:

- **Deadband**: a score within `deadband` of its threshold is satisfactory unless
  the dimension is explicitly Blocked. Do not choose `refine` to chase a score of
  6.3 against a 6.5 threshold.

- **Saturation**: if the trajectory shows a dimension has been targeted for
  refinement across multiple rounds with little or no improvement, further
  refinement is unlikely to help. Prefer `clarify` — the information needed is
  probably not in the brief.

- **Regression**: if the delta table shows dimensions that regressed this round, the
  refinement process may be making the brief worse, not better. A human is probably
  needed. Strongly prefer `clarify` and surface the regression in the rationale.

- **Max rounds**: if `current_round == max_rounds`, do not choose `refine`.
  Choose `proceed` if gaps are minor, `clarify` if any dimension is Blocked or
  regressing.

- **Partial failure**: if any advisor failed to score, treat their domain as
  uncertain. Do not proceed if the failed domain is critical to the decision.

### Judge output

```json
{
  "action": "refine",
  "agent_to_refine": "engineering_lead",
  "refinement_prompt": "The scalability section is absent. Propose a data volume
                        estimate and identify the two highest-concurrency paths.",
  "summary": "PM feedback is strong. Engineering flagged a scalability gap that
              can be filled from existing context.",
  "rationale": "Eng score is below threshold. No regression detected. One round
                remaining — targeted refinement is appropriate."
}
```

```json
{
  "action": "clarify",
  "questions": [
    "How many concurrent users are expected at launch?",
    "Does this need to integrate with the existing billing system?"
  ],
  "summary": "Engineering scores are regressing. Scalability and API design both
              dropped this round despite a refinement attempt.",
  "rationale": "Regression across two engineering dimensions suggests the brief
                lacks information the advisor needs. A human is likely required."
}
```

```json
{
  "action": "proceed",
  "unresolved": ["eng / Scalability", "eng / API design"],
  "summary": "PM feedback is strong. Engineering gaps remain but are below Blocked
              and within deadband. Both will appear as investigation tasks.",
  "rationale": "Scores meet standard-review thresholds within deadband. Remaining
                gaps are appropriate to investigate during execution."
}
```

### Refine path

1. The named advisor receives the current brief and `refinement_prompt`.
2. It produces updated content, subject to `config.timeouts.refine_step_seconds`.
3. A new brief version is written (`source = 'advisor_refinement'`,
   `workflow_round_id = current round`).
4. The current round is marked `completed`.
5. The executor reads `loop_until`, sees the action was not `proceed`, creates a
   new round, and returns to position 0.

### Clarify path

1. The judge step result is written with `status = 'awaiting_input'`.
2. The run is set to `awaiting_input` (existing mechanism).
3. The UI surfaces the team lead card with the questions and answer form.
4. When the user submits answers:
   - A new brief version is written (`source = 'user_clarification'`,
     `workflow_round_id = current round`).
   - The current round is marked `completed`.
   - A new round is created. Execution resumes from position 0.

### Proceed path

1. The current round is marked `completed`.
2. The executor reads `loop_until`, sees the action matches `proceed`, and advances
   to position 3.
3. The task planner receives the latest brief **plus** the list of `unresolved`
   dimensions from the judge's decision. For each unresolved dimension it generates
   a targeted investigation task, e.g.:

   > *"Clarify expected user concurrency — Engineering scored scalability as unclear
   > and this must be resolved before infrastructure decisions are made."*

4. Task generation runs immediately. No user confirmation is required.

---

## Frontend changes

### WorkflowStepper

The step list renders as a narrative feed:

- **Advisor review cards** (`output_kind: score`): show the advisor's name, overall
  status (Clear / Needs work / Blocked), and per-dimension breakdown with rationale
  text. If a score step failed or timed out, the card shows "Could not complete
  review" with the reason. Numeric scores are not shown in the primary view.

- **Team lead cards** (`output_kind: decision`): shown most prominently. Display
  the summary, action taken, and why. For clarify actions the answer form is inline.
  For proceed actions the card transitions to show task generation progress. If
  `unresolved` dimensions are present, the card notes they will appear as
  investigation tasks.

- **Brief change indicators**: when an advisor has updated the brief, a labelled
  callout shows what changed and who changed it, with a link to the full diff.

Round numbers are stored in the data but not shown in the UI.

### Brief diff view

A "See what changed" link appears after each advisor refinement. It opens a diff
of the brief before and after that edit, with the advisor and their rationale
alongside. Required, not optional.

### Scrutiny selector

> **How much scrutiny does this idea need?**
> ○ Quick check — experiments and prototypes
> ● Standard review — most features
> ○ Rigorous review — high-stakes or hard-to-reverse decisions

---

## Migration from current workflow

The current four-step linear workflow continues to work unchanged. This is a new
workflow type.

New columns (`round_id`, `score_run_id`, `execution_mode`, `loop_until`) are
nullable or have defaults so existing rows remain valid. New tables
(`workflow_rounds`, `workflow_judge_records`, `project_briefs`) are additive. The
existing `score-project!` code path is reused by score steps.
