
-- Start with 1000 to allow space for seeders
CREATE SEQUENCE groups_id_seq START WITH 1000;

--;;

-- Start with 1000 to allow space for seeders
CREATE SEQUENCE user_group_memberships_id_seq START WITH 1000;

--;;

CREATE TABLE groups(
       id                BIGINT DEFAULT nextval('groups_id_seq') PRIMARY KEY,
       display_name      VARCHAR (100),
       -- This corresponds to the `sub` field in a Keycloak access token.
       -- The `sub` field is the users UUID.
       owner_id          VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP
);

--;;

CREATE TABLE user_group_memberships(
       id                BIGINT DEFAULT nextval('user_group_memberships_id_seq') PRIMARY KEY,
       -- This corresponds to the `sub` field in a Keycloak access token.
       -- The `sub` field is the users UUID.
       user_id           VARCHAR (50),
       group_id          BIGINT,
       created_at        TIMESTAMP
);
