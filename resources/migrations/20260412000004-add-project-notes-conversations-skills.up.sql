CREATE TABLE project_notes (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  content    TEXT NOT NULL,
  source     TEXT NOT NULL DEFAULT 'text',
  created_at TIMESTAMP DEFAULT now()
);

--;;

CREATE TABLE project_conversations (
  id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  agent_type TEXT NOT NULL,
  role       TEXT NOT NULL,
  content    TEXT NOT NULL,
  created_at TIMESTAMP DEFAULT now()
);

--;;

CREATE TABLE project_skills (
  id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  project_id  UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  parent_id   UUID REFERENCES project_skills(id),
  name        TEXT NOT NULL,
  description TEXT,
  status      TEXT NOT NULL DEFAULT 'identified',
  position    INT NOT NULL DEFAULT 0,
  created_at  TIMESTAMP DEFAULT now()
);
