
CREATE TABLE groups(
       id                VARCHAR (36) PRIMARY KEY, --uuid
       display_name      VARCHAR (100),
       -- This corresponds to the `sub` field in a Keycloak access token.
       -- The `sub` field is the users UUID.
       owner_id          VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP
);

--;;

CREATE TABLE user_group_memberships(
       id                VARCHAR (36) PRIMARY KEY, --uuid
       email             VARCHAR,        -- this is the users id
       alias             VARCHAR,        -- nickname
       group_id          VARCHAR (36),   -- uuid
       created_at        TIMESTAMP,

       UNIQUE(group_id, email)
);

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
FROM groups g INNER JOIN
     user_group_memberships ugm ON g.id = ugm.group_id
