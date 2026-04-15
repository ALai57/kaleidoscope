-- Workspace roots: user-registered directories where codebases live
CREATE TABLE user_workspace_roots (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    TEXT NOT NULL,
  path       TEXT NOT NULL,
  label      TEXT,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  UNIQUE (user_id, path)
);

--;;

-- Per-project explicit code paths (manual override; empty = use auto-detection)
ALTER TABLE projects
  ADD COLUMN local_paths JSONB NOT NULL DEFAULT '[]';

--;;

-- Step requirements: declared inputs a step needs before it can execute
ALTER TABLE workflow_steps
  ADD COLUMN requires JSONB NOT NULL DEFAULT '[]';

--;;

-- Copy requires to step runs (denormalised for hot-path access, same pattern as output-kind etc.)
ALTER TABLE project_workflow_step_runs
  ADD COLUMN requires JSONB NOT NULL DEFAULT '[]';

--;;

-- Pending inputs: what the step is waiting for when status = 'awaiting_input' due to a requirement
ALTER TABLE project_workflow_step_runs
  ADD COLUMN pending_inputs JSONB;
