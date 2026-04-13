DROP INDEX IF EXISTS project_tasks_project_id_position_idx;

--;;

DROP INDEX IF EXISTS project_tasks_project_id_status_idx;

--;;

DROP TRIGGER IF EXISTS project_tasks_updated_at ON project_tasks;

--;;

DROP FUNCTION IF EXISTS set_updated_at();

--;;

DROP TABLE IF EXISTS project_tasks;

--;;

DROP TABLE IF EXISTS project_task_generation_runs;

--;;

ALTER TABLE project_workflow_step_runs
  DROP COLUMN IF EXISTS output_kind;

--;;

ALTER TABLE workflow_steps
  DROP COLUMN IF EXISTS output_kind;
