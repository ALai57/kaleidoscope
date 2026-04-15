-- Revert to the original constraint (no ON DELETE action)
ALTER TABLE project_workflow_step_runs
  DROP CONSTRAINT IF EXISTS fk_step_run_score_run;

--;;

ALTER TABLE project_workflow_step_runs
  ADD CONSTRAINT fk_step_run_score_run
  FOREIGN KEY (score_run_id) REFERENCES project_score_runs(id);
