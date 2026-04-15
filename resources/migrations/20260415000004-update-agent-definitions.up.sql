-- Rename display_name → name to match the frontend Agent type
ALTER TABLE agent_definitions
  RENAME COLUMN display_name TO name;

--;;

-- Add short_name (abbreviated label shown on cards) and color (avatar background)
ALTER TABLE agent_definitions
  ADD COLUMN short_name TEXT NOT NULL DEFAULT '',
  ADD COLUMN color      TEXT NOT NULL DEFAULT '';
