-- AI-engine tenancy, phase 1 (additive + safe): give every AI-engine table a
-- hostname column and backfill existing rows to the primary site. Nullable for
-- now so inserts keep working while the api/handler/persistence code is threaded
-- to set it; a later migration enforces NOT NULL + composite (parent_id,
-- hostname) FKs once every write supplies hostname. All existing AI-engine data
-- belongs to andrewslai.com (owner confirmed 2026-07-18).
ALTER TABLE projects ADD COLUMN hostname VARCHAR;

--;;

UPDATE projects SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE workflows ADD COLUMN hostname VARCHAR;

--;;

UPDATE workflows SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE score_definitions ADD COLUMN hostname VARCHAR;

--;;

UPDATE score_definitions SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE agent_definitions ADD COLUMN hostname VARCHAR;

--;;

UPDATE agent_definitions SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE user_workspace_roots ADD COLUMN hostname VARCHAR;

--;;

UPDATE user_workspace_roots SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE interests ADD COLUMN hostname VARCHAR;

--;;

UPDATE interests SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE workflow_steps ADD COLUMN hostname VARCHAR;

--;;

UPDATE workflow_steps SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_workflow_runs ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_workflow_runs SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_workflow_step_runs ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_workflow_step_runs SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_score_runs ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_score_runs SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_score_dimensions ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_score_dimensions SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE score_dimension_definitions ADD COLUMN hostname VARCHAR;

--;;

UPDATE score_dimension_definitions SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_notes ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_notes SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_conversations ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_conversations SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_skills ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_skills SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE workflow_rounds ADD COLUMN hostname VARCHAR;

--;;

UPDATE workflow_rounds SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE workflow_judge_records ADD COLUMN hostname VARCHAR;

--;;

UPDATE workflow_judge_records SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_briefs ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_briefs SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_task_generation_runs ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_task_generation_runs SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE project_tasks ADD COLUMN hostname VARCHAR;

--;;

UPDATE project_tasks SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE task_artifacts ADD COLUMN hostname VARCHAR;

--;;

UPDATE task_artifacts SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
--;;

ALTER TABLE recommendations ADD COLUMN hostname VARCHAR;

--;;

UPDATE recommendations SET hostname = 'andrewslai.com' WHERE hostname IS NULL;
