CREATE TABLE project_score_runs (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id          UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  score_definition_id UUID NOT NULL REFERENCES score_definitions(id),
  version             INT NOT NULL,
  overall             NUMERIC(4,2),
  scored_at           TIMESTAMP DEFAULT now()
);

--;;

CREATE TABLE project_score_dimensions (
  id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  score_run_id   UUID NOT NULL REFERENCES project_score_runs(id) ON DELETE CASCADE,
  dimension_name TEXT NOT NULL,
  value          NUMERIC(4,2),
  rationale      TEXT
);
