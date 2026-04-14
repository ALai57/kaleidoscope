DROP TABLE IF EXISTS project_briefs;

--;;

DROP TABLE IF EXISTS workflow_judge_records;

--;;

ALTER TABLE project_workflow_step_runs
  DROP CONSTRAINT IF EXISTS fk_step_run_score_run;

--;;

ALTER TABLE project_workflow_step_runs
  DROP CONSTRAINT IF EXISTS fk_step_run_round;

--;;

DROP TABLE IF EXISTS workflow_rounds;

--;;

ALTER TABLE project_workflow_step_runs
  DROP COLUMN IF EXISTS score_run_id,
  DROP COLUMN IF EXISTS round_id,
  DROP COLUMN IF EXISTS loop_until,
  DROP COLUMN IF EXISTS execution_mode;

--;;

ALTER TABLE project_score_runs
  DROP COLUMN IF EXISTS brief_version;

--;;

ALTER TABLE project_workflow_runs
  DROP COLUMN IF EXISTS config;

--;;

ALTER TABLE workflow_steps
  DROP COLUMN IF EXISTS loop_until,
  DROP COLUMN IF EXISTS execution_mode;
