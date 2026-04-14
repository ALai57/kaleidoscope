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
list is ready.

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
                    │  Team lead reads all feedback │
                    │  and decides what to do next  │
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
| **Quick check** | Experiments, throwaway prototypes | Lower score thresholds, max 1 round |
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

The overall status drives the team lead's decision. The per-dimension rationale
tells the user (and the advisor doing the refinement) exactly what to address.
Numeric scores exist in the data but are not shown in the primary UI.

---

## The team lead card

The team lead's output is the most prominent element in the review. It shows what
the team found, what action was taken, and why.

When the action is **proceed**, task generation starts immediately and the team lead
card summarises the outcome:

```
┌─────────────────────────────────────────────────────┐
│ Team Lead                                           │
├─────────────────────────────────────────────────────┤
│ PM feedback is strong. The scalability gap in the  │
│ engineering review has been addressed by the Eng   │
│ Lead. Remaining gaps (API interface detail) are    │
│ minor enough to handle during execution.           │
│                                                     │
│ Generating task list…                               │
└─────────────────────────────────────────────────────┘
```

When the action is **refine**, the card shows what the advisor is fixing and why,
and the review continues automatically. When the action is **needs your input**, the
card shows the questions and an answer form (existing awaiting_input mechanism).

---

## The brief

When the user creates a project they write a description. That description is
preserved exactly as written — it is a fact, not subject to change.

The advisors work from a **brief** — a living document that starts as a copy of the
description. Advisor refinements update the brief. User answers are appended to it.
All scoring is computed against the current brief, not the original description.

The user sees one thing: their idea, as it currently stands. When an advisor has
added or changed something, it is shown with clear attribution:

> *Engineering Lead added context on scalability*
> *You added context on expected user volume*

A diff view shows exactly what changed. This is not optional — silent autonomous
edits to a document the user owns create distrust.

The brief is versioned internally. Every score record links to the brief version it
evaluated, so the history is fully reproducible.

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

The round's outcome (refine / clarify / proceed) is derived by reading the
`output_kind: decision` step result that belongs to this round. It is not
denormalised onto the round row.

### Modified table: `project_workflow_step_runs`

```sql
ALTER TABLE project_workflow_step_runs
  ADD COLUMN round_id      UUID REFERENCES workflow_rounds(id),
  ADD COLUMN score_run_id  UUID REFERENCES score_runs(id);
```

`round_id` places the step result inside its round. `score_run_id` is set on score
steps and is the single authoritative reference to the score record. Score data is
not duplicated in the step result output field.

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

`workflow_round_id` replaces a bare integer. A brief version produced in round 2 of
run A is distinct from a brief version produced in round 2 of run B. The FK makes
that provenance unambiguous.

On project creation a version-1 brief is inserted with `source = 'initial'` and
`content = description`. The description column on `projects` is not modified again.

### Modified table: `project_workflow_runs`

```sql
ALTER TABLE project_workflow_runs
  ADD COLUMN config JSONB NOT NULL DEFAULT '{}';
```

The `config` column carries the policy block derived from the scrutiny level:

```json
{
  "scrutiny": "standard",
  "max_rounds": 2,
  "thresholds": { "pm": 6.5, "engineering_lead": 6.0, "default": 6.0 }
}
```

### Modified table: `score_runs`

```sql
ALTER TABLE score_runs
  ADD COLUMN brief_version INT;
```

Records which brief version was scored, closing the provenance loop.

### Modified table: `workflow_steps`

```sql
ALTER TABLE workflow_steps
  ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'sequential',
  ADD COLUMN loop_until     TEXT;
```

`execution_mode` separates execution behaviour from output shape (see below).
`loop_until` carries the action value that terminates the loop; null means no loop.

### `output_kind` and `execution_mode` values

`output_kind` describes the **shape** of a step's output. `execution_mode` describes
**how the executor runs the step** relative to its neighbours. These are independent
dimensions and are stored in separate columns.

**`output_kind` values:**

| value | output shape |
|---|---|
| `text` | Markdown prose |
| `clarify` | JSON `{ready, reply}` |
| `tasks` | JSON array of task objects |
| `score` | References a `score_run`; numeric dimensions stored in `project_score_dimensions` |
| `decision` | JSON routing decision `{action, summary, rationale, …}` |

**`execution_mode` values:**

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

The `loop_until: proceed` field on the judge step makes the loop contract explicit
in the definition. The executor does not need special knowledge about `decision`
steps — it reads `loop_until`, checks whether the step's output action matches, and
either creates a new round or continues to position 3. A workflow with no
`loop_until` on any step is purely linear. A workflow with `loop_until` on a
non-judge step is equally valid.

Positions 0–2 form the loop body by virtue of the judge step's position and its
`loop_until` value. Position 3 is outside the loop because no step at or after it
carries `loop_until`.

---

## Execution model

### Round lifecycle

When a run begins, the executor creates round 1. Each round:

1. **Score (parallel)**: all `execution_mode: parallel` steps launch as concurrent
   futures. Each scores the current brief version and writes a `score_run` and
   dimension rows, then creates a step result with `score_run_id` set.

2. **Fan-in**: once all score futures reach a terminal state (completed or failed),
   the `execution_mode: fan_in` step runs. Partial failure is handled here — see
   below.

3. **Routing**: the judge reads its `loop_until` value from the step template and
   compares it against the `action` field in its output. If they match, execution
   advances to position 3. If not, a new round is created and execution returns to
   position 0.

### Partial score failure

If a score step fails, its step result is marked `failed`. The fan-in step still
runs once all steps have reached a terminal state. The judge receives:

- Completed scores: full structured data as normal
- Failed scores: a sentinel entry `{"failed": true, "agent": "engineering_lead"}`

The judge's system prompt instructs it to handle missing scores explicitly: note
the failure in the summary, bias toward `clarify` if a failed advisor's domain is
critical to the decision, and avoid proceeding when key evidence is absent. The
judge does not pretend missing scores are satisfactory; it accounts for them.

### Partial failure at the round level

A round is marked `completed` when the fan-in step finishes, regardless of whether
any score steps failed. A round is only marked `failed` if the judge step itself
fails. This keeps the failure semantics narrow: a round represents a complete
deliberation attempt, and partial evidence is still deliberation.

### Judge input

The judge step receives:

- The current brief (latest version)
- All score step results for this round (including any failure sentinels)
- The policy config: `{scrutiny, max_rounds, thresholds, current_round}`

`current_round` and `max_rounds` are passed as data. The judge's system prompt
instructs it to read these values and not choose `refine` when
`current_round >= max_rounds`. The prompt is static; behaviour varies because
the data varies.

### Judge output

```json
{
  "action": "refine",
  "agent_to_refine": "engineering_lead",
  "refinement_prompt": "The scalability section is absent. Propose a data volume
                        estimate and identify the two highest-concurrency paths.",
  "summary": "PM feedback is strong. Engineering flagged a critical scalability gap
              that can be filled from existing context.",
  "rationale": "Eng score is below threshold and the gap is addressable without
                user input."
}
```

```json
{
  "action": "clarify",
  "questions": [
    "How many concurrent users are expected at launch?",
    "Does this need to integrate with the existing billing system?"
  ],
  "summary": "PM feedback is strong. Engineering cannot assess scalability without
              knowing expected load.",
  "rationale": "Scale requirements are absent and cannot be inferred."
}
```

```json
{
  "action": "proceed",
  "summary": "Both advisors are satisfied. The remaining API design gap is minor
              and appropriate to address during execution.",
  "rationale": "Scores meet standard-review thresholds. No blockers remain."
}
```

### Refine path

1. The named advisor receives the current brief and the `refinement_prompt`.
2. It produces updated content.
3. A new brief version is written (`source = 'advisor_refinement'`,
   `workflow_round_id = current round`).
4. The current round is marked `completed`.
5. The executor reads `loop_until` on the judge step, sees the action was `refine`
   (not `proceed`), creates a new round, and returns to position 0.

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
2. The executor reads `loop_until`, sees the action matches (`proceed`), and
   advances to position 3.
3. Task generation runs immediately against the latest brief version.
4. No user confirmation is required.

---

## Frontend changes

### WorkflowStepper

The step list renders as a narrative feed:

- **Advisor review cards** (`output_kind: score`): show the advisor's name, overall
  status (Clear / Needs work / Blocked), and a per-dimension breakdown with rationale
  text. If a score step failed, the card shows "Could not complete review" with the
  failure reason. Numeric scores are not shown in the primary view.

- **Team lead cards** (`output_kind: decision`): shown most prominently. Display the
  summary, the action taken, and why. For clarify actions, the answer form is inline
  on this card (existing awaiting_input mechanism). For proceed actions, the card
  transitions to show task generation progress inline — no separate button or
  confirmation step.

- **Brief change indicators**: when an advisor has updated the brief, a labelled
  callout shows what changed and who changed it, with a link to the full diff. This
  appears inline in the feed at the point where the change was made.

Round numbers are stored in the data but not shown in the UI. If a second or third
review cycle occurs, the feed simply continues.

### Brief diff view

A "See what changed" link appears after each advisor refinement. It opens a diff of
the brief before and after that edit, with the advisor and their rationale alongside.
This is a required feature — it is the only way the user can verify that autonomous
edits match their intent.

### Scrutiny selector

When starting a run (or as part of project creation), the user sees:

> **How much scrutiny does this idea need?**
> ○ Quick check — experiments and prototypes
> ● Standard review — most features
> ○ Rigorous review — high-stakes or hard-to-reverse decisions

Their choice sets `config.scrutiny` and the derived thresholds and round limit.

---

## Migration from current workflow

The current four-step linear workflow continues to work unchanged. This is a new
workflow type.

New columns (`round_id`, `score_run_id`, `execution_mode`, `loop_until`) are
nullable or have defaults so existing rows remain valid. New tables
(`workflow_rounds`, `project_briefs`) are additive. The existing `score-project!`
code path is reused by score steps — they produce the same `score_run` rows; the
workflow executor is the only new consumer.
