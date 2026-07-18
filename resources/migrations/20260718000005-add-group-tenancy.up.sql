-- Groups become per-site (owner confirmed 2026-07-18): a group belongs to a
-- tenant, and its memberships inherit that tenant. Groups back both article and
-- recipe audiences; the audience-attach checks remain the enforcement point.
ALTER TABLE groups ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE groups SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE groups ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE groups ADD CONSTRAINT groups_id_hostname_unique UNIQUE (id, hostname);

--;;

ALTER TABLE user_group_memberships ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE user_group_memberships SET hostname =
  (SELECT g.hostname FROM groups g WHERE g.id = user_group_memberships.group_id)
  WHERE hostname IS NULL;

--;;

-- Any membership whose group no longer exists (orphan) still needs a tenant.
UPDATE user_group_memberships SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE user_group_memberships ALTER COLUMN hostname SET NOT NULL;

--;;

DROP VIEW IF EXISTS full_memberships;

--;;

CREATE VIEW full_memberships AS
SELECT
       g.id as group_id,
       g.hostname,
       g.created_at as group_created_at,
       g.modified_at as group_modified_at,
       g.display_name,
       g.owner_id,
       ugm.id as membership_id,
       ugm.email,
       ugm.alias,
       ugm.created_at as membership_created_at
FROM groups g LEFT JOIN
     user_group_memberships ugm ON g.id = ugm.group_id;
