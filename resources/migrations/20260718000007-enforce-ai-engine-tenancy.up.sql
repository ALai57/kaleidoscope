-- AI-engine tenancy, phase 2 (enforcement). Every AI-engine row now carries a
-- hostname (phase 1 + code threading); make it DB-enforced like the CMS:
-- NOT NULL everywhere, UNIQUE(id, hostname) on FK-target parents, and composite
-- (fk_col, hostname) FKs so a child cannot attach to another tenant's parent.
-- Existing rows were backfilled to andrewslai.com in phase 1.

--;;

ALTER TABLE projects ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE workflows ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE score_definitions ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE agent_definitions ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE user_workspace_roots ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE interests ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE workflow_steps ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_workflow_runs ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_workflow_step_runs ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_score_runs ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_score_dimensions ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE score_dimension_definitions ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_notes ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_conversations ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_skills ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE workflow_rounds ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE workflow_judge_records ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_briefs ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_task_generation_runs ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE project_tasks ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE task_artifacts ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE recommendations ALTER COLUMN hostname SET NOT NULL;
--;;

ALTER TABLE projects ADD CONSTRAINT projects_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE workflows ADD CONSTRAINT workflows_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE score_definitions ADD CONSTRAINT score_definitions_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE project_workflow_runs ADD CONSTRAINT project_workflow_runs_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE project_workflow_step_runs ADD CONSTRAINT project_workflow_step_runs_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE project_score_runs ADD CONSTRAINT project_score_runs_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE project_tasks ADD CONSTRAINT project_tasks_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE interests ADD CONSTRAINT interests_id_hostname_unique UNIQUE (id, hostname);
--;;

ALTER TABLE workflow_steps ADD CONSTRAINT workflow_steps_workflows_hostname_fk FOREIGN KEY (workflow_id, hostname) REFERENCES workflows (id, hostname);
--;;

ALTER TABLE project_workflow_runs ADD CONSTRAINT project_workflow_runs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE project_workflow_step_runs ADD CONSTRAINT project_workflow_step_runs_project_workflow_runs_hostname_fk FOREIGN KEY (workflow_run_id, hostname) REFERENCES project_workflow_runs (id, hostname);
--;;

ALTER TABLE project_score_runs ADD CONSTRAINT project_score_runs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE project_score_dimensions ADD CONSTRAINT project_score_dimensions_project_score_runs_hostname_fk FOREIGN KEY (score_run_id, hostname) REFERENCES project_score_runs (id, hostname);
--;;

ALTER TABLE score_dimension_definitions ADD CONSTRAINT score_dimension_definitions_score_definitions_hostname_fk FOREIGN KEY (score_definition_id, hostname) REFERENCES score_definitions (id, hostname);
--;;

ALTER TABLE project_notes ADD CONSTRAINT project_notes_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE project_conversations ADD CONSTRAINT project_conversations_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE project_skills ADD CONSTRAINT project_skills_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE workflow_rounds ADD CONSTRAINT workflow_rounds_project_workflow_runs_hostname_fk FOREIGN KEY (workflow_run_id, hostname) REFERENCES project_workflow_runs (id, hostname);
--;;

ALTER TABLE workflow_judge_records ADD CONSTRAINT workflow_judge_records_project_workflow_step_runs_hostname_fk FOREIGN KEY (step_run_id, hostname) REFERENCES project_workflow_step_runs (id, hostname);
--;;

ALTER TABLE project_briefs ADD CONSTRAINT project_briefs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE project_task_generation_runs ADD CONSTRAINT project_task_generation_runs_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE project_tasks ADD CONSTRAINT project_tasks_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE task_artifacts ADD CONSTRAINT task_artifacts_project_tasks_hostname_fk FOREIGN KEY (task_id, hostname) REFERENCES project_tasks (id, hostname);
--;;

ALTER TABLE interests ADD CONSTRAINT interests_projects_hostname_fk FOREIGN KEY (project_id, hostname) REFERENCES projects (id, hostname);
--;;

ALTER TABLE recommendations ADD CONSTRAINT recommendations_interests_hostname_fk FOREIGN KEY (interest_id, hostname) REFERENCES interests (id, hostname);
