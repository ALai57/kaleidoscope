ALTER TABLE agent_definitions
  DROP COLUMN IF EXISTS short_name;

--;;

ALTER TABLE agent_definitions
  DROP COLUMN IF EXISTS color;

--;;

ALTER TABLE agent_definitions
  RENAME COLUMN name TO display_name;
