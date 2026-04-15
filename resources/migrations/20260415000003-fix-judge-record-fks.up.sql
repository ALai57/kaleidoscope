-- Several FKs added in 20260414000001 are missing cascade behaviour.
-- When a project is deleted the cascade chain is:
--   projects → project_workflow_runs → project_workflow_step_runs
--                                    → workflow_rounds
-- Any FK that points INTO this chain without ON DELETE CASCADE / SET NULL
-- blocks the deletion.

-- 1. step_run_id on judge records: records are owned by the step run
--    → cascade-delete when the step run is deleted
ALTER TABLE workflow_judge_records
  DROP CONSTRAINT IF EXISTS workflow_judge_records_step_run_id_fkey;

--;;

ALTER TABLE workflow_judge_records
  ADD CONSTRAINT workflow_judge_records_step_run_id_fkey
  FOREIGN KEY (step_run_id) REFERENCES project_workflow_step_runs(id) ON DELETE CASCADE;

--;;

-- 2. round_id on judge records: records are owned by the round
--    → cascade-delete when the round is deleted
ALTER TABLE workflow_judge_records
  DROP CONSTRAINT IF EXISTS workflow_judge_records_round_id_fkey;

--;;

ALTER TABLE workflow_judge_records
  ADD CONSTRAINT workflow_judge_records_round_id_fkey
  FOREIGN KEY (round_id) REFERENCES workflow_rounds(id) ON DELETE CASCADE;

--;;

-- 3. round_id on step runs: nullable column, clear it when the round is removed
ALTER TABLE project_workflow_step_runs
  DROP CONSTRAINT IF EXISTS fk_step_run_round;

--;;

ALTER TABLE project_workflow_step_runs
  ADD CONSTRAINT fk_step_run_round
  FOREIGN KEY (round_id) REFERENCES workflow_rounds(id) ON DELETE SET NULL;

--;;

-- 4. workflow_round_id on project briefs: nullable column, clear it when the round is removed
ALTER TABLE project_briefs
  DROP CONSTRAINT IF EXISTS project_briefs_workflow_round_id_fkey;

--;;

ALTER TABLE project_briefs
  ADD CONSTRAINT project_briefs_workflow_round_id_fkey
  FOREIGN KEY (workflow_round_id) REFERENCES workflow_rounds(id) ON DELETE SET NULL;
