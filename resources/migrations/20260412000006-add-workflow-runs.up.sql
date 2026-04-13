CREATE TABLE project_workflow_runs (
  id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id   UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  workflow_id  UUID REFERENCES workflows(id),
  status       TEXT NOT NULL DEFAULT 'pending',
  current_step INT NOT NULL DEFAULT 0,
  mode         TEXT NOT NULL DEFAULT 'manual',
  started_at   TIMESTAMP,
  completed_at TIMESTAMP,
  created_at   TIMESTAMP DEFAULT now()
);

--;;

CREATE TABLE project_workflow_step_runs (
  id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  workflow_run_id UUID NOT NULL REFERENCES project_workflow_runs(id) ON DELETE CASCADE,
  step_id         UUID REFERENCES workflow_steps(id),
  position        INT NOT NULL,
  name            TEXT NOT NULL,
  description     TEXT NOT NULL,
  agent_type      TEXT NOT NULL DEFAULT 'coach',
  is_custom       BOOLEAN NOT NULL DEFAULT false,
  status          TEXT NOT NULL DEFAULT 'pending',
  output          TEXT,
  started_at      TIMESTAMP,
  completed_at    TIMESTAMP
);
