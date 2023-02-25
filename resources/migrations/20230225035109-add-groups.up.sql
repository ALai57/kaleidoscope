
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
       -- This corresponds to the `sub` field in a Keycloak access token.
       -- The `sub` field is the users UUID.
       user_id           VARCHAR (36), --uuid
       group_id          VARCHAR (36), --uuid
       created_at        TIMESTAMP,

       UNIQUE(user_id, group_id)
);
