CREATE TABLE agent_definitions (
  id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id       TEXT NOT NULL,
  agent_type    TEXT NOT NULL,
  display_name  TEXT NOT NULL,
  avatar        TEXT NOT NULL,
  system_prompt TEXT NOT NULL,
  is_default    BOOLEAN NOT NULL DEFAULT false,
  created_at    TIMESTAMP DEFAULT now(),
  updated_at    TIMESTAMP DEFAULT now(),
  UNIQUE (user_id, agent_type)
);

--;;

ALTER TABLE workflow_steps
  ADD COLUMN agent_type TEXT NOT NULL DEFAULT 'coach';
