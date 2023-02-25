
DROP SEQUENCE IF EXISTS groups_id_seq;

--;;

-- Start with 1000 to allow space for seeders
DROP SEQUENCE IF EXISTS user_group_memberships_id_seq;

--;;

DROP TABLE IF EXISTS groups;

--;;

DROP TABLE IF EXISTS user_group_memberships;
