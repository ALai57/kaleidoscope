CREATE TABLE score_definitions (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id     TEXT NOT NULL,
  name        TEXT NOT NULL,
  description TEXT NOT NULL,
  scorer_type TEXT NOT NULL DEFAULT 'general',
  is_default  BOOLEAN NOT NULL DEFAULT false,
  created_at  TIMESTAMP DEFAULT now(),
  updated_at  TIMESTAMP DEFAULT now()
);

--;;

CREATE TABLE score_dimension_definitions (
  id                  UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  score_definition_id UUID NOT NULL REFERENCES score_definitions(id) ON DELETE CASCADE,
  name                TEXT NOT NULL,
  criteria            TEXT NOT NULL,
  position            INT NOT NULL DEFAULT 0
);
