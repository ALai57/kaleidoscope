-- Execution artifacts attached to individual tasks.
-- artifact_type: "research_summary" | "code_patch" | "calendar_event"
-- content holds the full text payload (summary text, diff, etc.)
-- url holds an external link (calendar event URL, etc.)
CREATE TABLE task_artifacts (
  id            UUID        DEFAULT gen_random_uuid() PRIMARY KEY,
  task_id       UUID        NOT NULL REFERENCES project_tasks(id) ON DELETE CASCADE,
  artifact_type TEXT        NOT NULL,
  content       TEXT,
  url           TEXT,
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--;;

CREATE INDEX idx_task_artifacts_task_id ON task_artifacts(task_id);
