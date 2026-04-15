ALTER TABLE workflow_judge_records
  DROP CONSTRAINT IF EXISTS workflow_judge_records_step_run_id_fkey;

--;;

ALTER TABLE workflow_judge_records
  ADD CONSTRAINT workflow_judge_records_step_run_id_fkey
  FOREIGN KEY (step_run_id) REFERENCES project_workflow_step_runs(id);
