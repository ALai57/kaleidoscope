CREATE TABLE themes(
       id                UUID NOT NULL PRIMARY KEY, --uuid
       display_name      VARCHAR (100),
       -- This corresponds to the `sub` field in a Keycloak access token.
       -- The `sub` field is the users UUID.
       config            JSONB,
       hostname          VARCHAR,
       owner_id          VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP
);

--;;
