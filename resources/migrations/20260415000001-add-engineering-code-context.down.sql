ALTER TABLE project_workflow_step_runs
  DROP COLUMN IF EXISTS pending_inputs;

--;;

ALTER TABLE project_workflow_step_runs
  DROP COLUMN IF EXISTS requires;

--;;

ALTER TABLE workflow_steps
  DROP COLUMN IF EXISTS requires;

--;;

ALTER TABLE projects
  DROP COLUMN IF EXISTS local_paths;

--;;

DROP TABLE IF EXISTS user_workspace_roots;
