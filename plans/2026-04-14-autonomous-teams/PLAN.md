# Score → Judge → Refine Workflow

## Concept

The user describes an idea. A team of advisors reviews it independently, each
from their own angle. The team lead reads all the feedback, fixes what can be fixed
without involving the user, and asks the user when something genuinely requires their
input. Once the team is satisfied — or satisfied enough — they produce a task list.

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

**Ready** — The team is satisfied. The user sees a summary of what was found and
confirmed, and a task list is ready. A "Continue" button lets them proceed.

There is no round counter, no numeric score, no mention of iterations. The system
tracks that information internally; the user does not need it.

---

## Core loop

```
                    ┌──────────────────────────────┐
                    │  Advisors review the idea     │
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
    autonomously             (pause for answer)       (proceed)
           │                       │                       │
    Advisor updates          User answers               Task list
    the brief. Loop          appended to brief.         generated.
    repeats.                 Loop repeats.
```

The loop runs until the team lead decides to proceed, or until the iteration limit
is reached. At the limit the team lead must choose between proceeding despite gaps
or asking the user — no further autonomous refinement is permitted.

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

The team lead's output is the most prominent element in the review. It shows:

- What the team found (summary across advisors)
- What action was taken or is being taken
- Why

When the action is **proceed**, the user sees a recommendation card and a
"Continue to tasks" button. The user is always the one who clicks Continue — the
team lead recommends, it does not decide unilaterally.

```
┌─────────────────────────────────────────────────────┐
│ Team Lead                                           │
├─────────────────────────────────────────────────────┤
│ PM feedback is strong. The scalability gap in the  │
│ engineering review has been addressed by the Eng   │
│ Lead. Remaining gaps (API interface detail) are    │
│ minor enough to handle during execution.           │
│                                                     │
│ Recommendation: proceed.                            │
│                                                     │
│                        [ Continue to tasks ]        │
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

> *Engineering Lead added context on scalability (Round 2)*
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
  judge_action    TEXT,           -- refine | clarify | proceed (null until judge completes)
  started_at      TIMESTAMP NOT NULL DEFAULT now(),
  completed_at    TIMESTAMP,
  UNIQUE (workflow_run_id, round_number)
);
```

Rounds are a data model concept used for provenance and history. They are not
surfaced as a UI concept.

### Modified table: `project_workflow_step_runs`

```sql
ALTER TABLE project_workflow_step_runs
  ADD COLUMN round_id      UUID REFERENCES workflow_rounds(id),
  ADD COLUMN score_run_id  UUID REFERENCES score_runs(id);
```

`round_id` places the step result inside its round. `score_run_id` is set on score
steps and is the reference to the authoritative score record. The step result does
not duplicate score data.

### New table: `project_briefs`

```sql
CREATE TABLE project_briefs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  version      INT  NOT NULL,
  content      TEXT NOT NULL,
  source       TEXT NOT NULL, -- 'initial' | 'advisor_refinement' | 'user_clarification'
  agent_type   TEXT,          -- set when source = 'advisor_refinement'
  round_number INT,           -- set when source != 'initial'
  created_at   TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, version)
);
```

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
  ADD COLUMN brief_version INT;  -- which brief version was scored
```

### `output_kind` values

Two new values join the existing set (`text`, `clarify`, `tasks`):

| value | meaning |
|---|---|
| `score` | Step produces a score run; `score_run_id` is set on the step result |
| `judge` | Step produces a routing decision; `judge_action` is set on the round |

---

## Workflow definition

```
position 0  output_kind: score   agent: pm                name: "PM review"
position 1  output_kind: score   agent: engineering_lead   name: "Engineering review"
position 2  output_kind: judge   agent: judge              name: "Team Lead"
position 3  output_kind: tasks   agent: task_planner       name: "Generate tasks"
```

Positions 0–2 are the loop body. Position 3 runs exactly once after a proceed
decision.

---

## Execution model

### Round lifecycle

When a run begins, the executor creates round 1. Each round:

1. **Score (parallel)**: all `score` steps launch as concurrent futures. Each scores
   the current brief version, writes a `score_run` and dimension rows, then creates
   a step result with `score_run_id` set. No score step waits for another.

2. **Judge (serial)**: once all score futures complete, the judge step runs. It
   receives the current brief, all score results for this round, and the policy
   config from `workflow_run.config`.

3. **Routing**: the judge's structured output determines the next action.

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
  "summary": "PM feedback is strong. Engineering cannot score scalability without
              knowing expected load.",
  "rationale": "Scale requirements are absent and cannot be inferred. User input
                is required before engineering can assess feasibility."
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
3. A new brief version is written (`source = 'advisor_refinement'`).
4. The current round is marked completed (`judge_action = 'refine'`).
5. A new round is created. Execution returns to the score phase.

### Clarify path

1. The judge step result is written with `status = 'awaiting_input'`.
2. The run is set to `awaiting_input` (existing mechanism).
3. The UI surfaces the team lead card with the questions and answer form.
4. When the user submits answers:
   - A new brief version is written (`source = 'user_clarification'`).
   - The current round is marked completed (`judge_action = 'clarify'`).
   - A new round is created. Execution resumes from the score phase.

### Proceed path

1. The current round is marked completed (`judge_action = 'proceed'`).
2. The team lead card is shown with a "Continue to tasks" button.
3. When the user clicks Continue, the execute step runs: task generation against
   the latest brief version.

### Max-round guard

Before creating a new round, check `round_number >= config.max_rounds`. If at the
limit, the judge prompt is amended: *"This is the final round. No further refinement
is permitted. Choose proceed or clarify."*

---

## Frontend changes

### WorkflowStepper

The step list renders as a narrative feed, not a ledger of rounds:

- **Advisor review cards** (`output_kind: score`): show the advisor's name, overall
  status (Clear / Needs work / Blocked), and a per-dimension breakdown with rationale
  text. Numeric scores are not shown in the primary view.

- **Team lead cards** (`output_kind: judge`): shown most prominently. Display the
  summary, the action taken, and (for proceed) the "Continue to tasks" button. For
  clarify actions, the answer form is inline on this card (existing awaiting_input
  mechanism, re-skinned to match).

- **Brief change indicators**: when an advisor has updated the brief, a labelled
  callout shows what changed and who changed it, with a link to the full diff. This
  appears inline in the narrative feed at the point where the change was made.

Round numbers are stored in the data but not shown as headers or labels in the UI.
If a second or third review cycle occurs, the feed simply continues — the user sees
advisors reviewing again, not "Round 2 begins."

### Brief diff view

A "See what changed" link appears after each advisor refinement. It opens a diff of
the brief before and after the advisor's edit, with the advisor and their rationale
shown alongside. This is a required feature, not optional — it is the only way the
user can verify that autonomous edits match their intent.

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

New columns (`round_id`, `score_run_id`) are nullable so existing step result rows
remain valid. New tables (`workflow_rounds`, `project_briefs`) are additive. The
existing `score-project!` code path is reused by score steps — they produce the
same `score_run` rows; the workflow executor is the only new consumer.
