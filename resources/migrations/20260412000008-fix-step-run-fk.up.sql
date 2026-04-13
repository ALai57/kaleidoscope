ALTER TABLE project_workflow_step_runs
  DROP CONSTRAINT project_workflow_step_runs_step_id_fkey;

--;;

ALTER TABLE project_workflow_step_runs
  ADD CONSTRAINT project_workflow_step_runs_step_id_fkey
  FOREIGN KEY (step_id) REFERENCES workflow_steps(id) ON DELETE SET NULL;
