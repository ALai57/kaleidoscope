-- Replace fk_step_run_score_run with ON DELETE SET NULL so that deleting a
-- score run clears the reference on the step run rather than raising a FK error.
ALTER TABLE project_workflow_step_runs
  DROP CONSTRAINT IF EXISTS fk_step_run_score_run;

--;;

ALTER TABLE project_workflow_step_runs
  ADD CONSTRAINT fk_step_run_score_run
  FOREIGN KEY (score_run_id) REFERENCES project_score_runs(id) ON DELETE SET NULL;
