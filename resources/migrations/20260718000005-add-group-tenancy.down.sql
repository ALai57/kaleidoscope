DROP VIEW IF EXISTS full_memberships;

--;;

ALTER TABLE user_group_memberships DROP COLUMN hostname;

--;;

ALTER TABLE groups DROP CONSTRAINT groups_id_hostname_unique;

--;;

ALTER TABLE groups DROP COLUMN hostname;

--;;

CREATE VIEW full_memberships AS
SELECT
       g.id as group_id,
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
