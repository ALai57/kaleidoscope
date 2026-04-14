-- Extend workflow step definitions with execution model fields
ALTER TABLE workflow_steps
  ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'sequential',
  ADD COLUMN loop_until     TEXT;

--;;

-- Extend step runs with execution model + round/score linkage
ALTER TABLE project_workflow_step_runs
  ADD COLUMN execution_mode TEXT NOT NULL DEFAULT 'sequential',
  ADD COLUMN loop_until     TEXT,
  ADD COLUMN round_id       UUID,
  ADD COLUMN score_run_id   UUID;

--;;

-- Add policy config to workflow runs (scrutiny level, thresholds, etc.)
ALTER TABLE project_workflow_runs
  ADD COLUMN config JSONB NOT NULL DEFAULT '{}';

--;;

-- Track which brief version each score run evaluated
ALTER TABLE project_score_runs
  ADD COLUMN brief_version INT;

--;;

-- Rounds: each pass through the parallel-score + judge loop
CREATE TABLE workflow_rounds (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_run_id UUID NOT NULL REFERENCES project_workflow_runs(id) ON DELETE CASCADE,
  round_number    INT  NOT NULL,
  status          TEXT NOT NULL DEFAULT 'in_progress',
  started_at      TIMESTAMP NOT NULL DEFAULT now(),
  completed_at    TIMESTAMP,
  UNIQUE (workflow_run_id, round_number)
);

--;;

-- FK from step runs to rounds (table must exist first)
ALTER TABLE project_workflow_step_runs
  ADD CONSTRAINT fk_step_run_round
  FOREIGN KEY (round_id) REFERENCES workflow_rounds(id);

--;;

-- FK from step runs to score runs
ALTER TABLE project_workflow_step_runs
  ADD CONSTRAINT fk_step_run_score_run
  FOREIGN KEY (score_run_id) REFERENCES project_score_runs(id);

--;;

-- Judge records: complete audit trail of every team-lead decision
CREATE TABLE workflow_judge_records (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  step_run_id    UUID  NOT NULL REFERENCES project_workflow_step_runs(id),
  round_id       UUID  NOT NULL REFERENCES workflow_rounds(id),
  brief_version  INT   NOT NULL,
  score_snapshot JSONB NOT NULL,
  trajectory     JSONB NOT NULL,
  delta_table    JSONB NOT NULL,
  policy         JSONB NOT NULL,
  decision       JSONB NOT NULL,
  created_at     TIMESTAMP NOT NULL DEFAULT now()
);

--;;

-- Versioned project briefs (living document scored each round)
CREATE TABLE project_briefs (
  id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id        UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  version           INT  NOT NULL,
  content           TEXT NOT NULL,
  source            TEXT NOT NULL,
  agent_type        TEXT,
  workflow_round_id UUID REFERENCES workflow_rounds(id),
  created_at        TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (project_id, version)
);
