ALTER TABLE recommendations DROP CONSTRAINT recommendations_interests_hostname_fk;
--;;

ALTER TABLE interests DROP CONSTRAINT interests_projects_hostname_fk;
--;;

ALTER TABLE task_artifacts DROP CONSTRAINT task_artifacts_project_tasks_hostname_fk;
--;;

ALTER TABLE project_tasks DROP CONSTRAINT project_tasks_projects_hostname_fk;
--;;

ALTER TABLE project_task_generation_runs DROP CONSTRAINT project_task_generation_runs_projects_hostname_fk;
--;;

ALTER TABLE project_briefs DROP CONSTRAINT project_briefs_projects_hostname_fk;
--;;

ALTER TABLE workflow_judge_records DROP CONSTRAINT workflow_judge_records_project_workflow_step_runs_hostname_fk;
--;;

ALTER TABLE workflow_rounds DROP CONSTRAINT workflow_rounds_project_workflow_runs_hostname_fk;
--;;

ALTER TABLE project_skills DROP CONSTRAINT project_skills_projects_hostname_fk;
--;;

ALTER TABLE project_conversations DROP CONSTRAINT project_conversations_projects_hostname_fk;
--;;

ALTER TABLE project_notes DROP CONSTRAINT project_notes_projects_hostname_fk;
--;;

ALTER TABLE score_dimension_definitions DROP CONSTRAINT score_dimension_definitions_score_definitions_hostname_fk;
--;;

ALTER TABLE project_score_dimensions DROP CONSTRAINT project_score_dimensions_project_score_runs_hostname_fk;
--;;

ALTER TABLE project_score_runs DROP CONSTRAINT project_score_runs_projects_hostname_fk;
--;;

ALTER TABLE project_workflow_step_runs DROP CONSTRAINT project_workflow_step_runs_project_workflow_runs_hostname_fk;
--;;

ALTER TABLE project_workflow_runs DROP CONSTRAINT project_workflow_runs_projects_hostname_fk;
--;;

ALTER TABLE workflow_steps DROP CONSTRAINT workflow_steps_workflows_hostname_fk;
--;;

ALTER TABLE interests DROP CONSTRAINT interests_id_hostname_unique;
--;;

ALTER TABLE project_tasks DROP CONSTRAINT project_tasks_id_hostname_unique;
--;;

ALTER TABLE project_score_runs DROP CONSTRAINT project_score_runs_id_hostname_unique;
--;;

ALTER TABLE project_workflow_step_runs DROP CONSTRAINT project_workflow_step_runs_id_hostname_unique;
--;;

ALTER TABLE project_workflow_runs DROP CONSTRAINT project_workflow_runs_id_hostname_unique;
--;;

ALTER TABLE score_definitions DROP CONSTRAINT score_definitions_id_hostname_unique;
--;;

ALTER TABLE workflows DROP CONSTRAINT workflows_id_hostname_unique;
--;;

ALTER TABLE projects DROP CONSTRAINT projects_id_hostname_unique;
--;;

ALTER TABLE recommendations ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE task_artifacts ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_tasks ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_task_generation_runs ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_briefs ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE workflow_judge_records ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE workflow_rounds ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_skills ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_conversations ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_notes ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE score_dimension_definitions ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_score_dimensions ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_score_runs ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_workflow_step_runs ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE project_workflow_runs ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE workflow_steps ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE interests ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE user_workspace_roots ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE agent_definitions ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE score_definitions ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE workflows ALTER COLUMN hostname DROP NOT NULL;
--;;

ALTER TABLE projects ALTER COLUMN hostname DROP NOT NULL;
