ALTER TABLE workflow_steps
  ADD COLUMN output_kind TEXT NOT NULL DEFAULT 'text';

--;;

ALTER TABLE project_workflow_step_runs
  ADD COLUMN output_kind TEXT NOT NULL DEFAULT 'text';

--;;

CREATE TABLE project_task_generation_runs (
  id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  project_id  UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id     TEXT NOT NULL,
  created_at  TIMESTAMP WITH TIME ZONE DEFAULT now()
);

--;;

CREATE TABLE project_tasks (
  id                    UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  project_id            UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  user_id               TEXT NOT NULL,
  title                 TEXT NOT NULL,
  description           TEXT,
  task_type             TEXT NOT NULL DEFAULT 'action',
  status                TEXT NOT NULL DEFAULT 'pending',
  position              INT NOT NULL DEFAULT 0,
  estimated_minutes     INT,
  generation_run_id     UUID REFERENCES project_task_generation_runs(id),
  workflow_step_run_id  UUID REFERENCES project_workflow_step_runs(id),
  created_at            TIMESTAMP WITH TIME ZONE DEFAULT now(),
  updated_at            TIMESTAMP WITH TIME ZONE DEFAULT now()
);

--;;

CREATE INDEX ON project_tasks (project_id, status);

--;;

CREATE INDEX ON project_tasks (project_id, position);
