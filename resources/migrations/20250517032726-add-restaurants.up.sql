
CREATE TABLE restaurants(
       id                UUID NOT NULL PRIMARY KEY,
       display_name      VARCHAR (100),
       url               VARCHAR,
       -- This corresponds to the `sub` field in a Keycloak access token.
       -- The `sub` field is the users UUID.
       owner_id          VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP
);

--;;

-- People who go to restaurants together. Basically, people with specific palettes
CREATE TABLE eater_groups(
       id                UUID NOT NULL PRIMARY KEY,
       display_name      VARCHAR (100),
       owner_id          VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP
);

--;;

CREATE TABLE eater_group_memberships(
       id                UUID NOT NULL PRIMARY KEY,
       email             VARCHAR,        -- this is the users id
       alias             VARCHAR,        -- nickname
       group_id          UUID NOT NULL,
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       UNIQUE(group_id, email)
);

--;;

-- Attach a group to a restaurant for permissioning
CREATE TABLE restaurant_audiences(
       id         UUID NOT NULL PRIMARY KEY,
       eater_group_id   UUID NOT NULL,
       restaurant_id UUID NOT NULL,
       created_at TIMESTAMP,
       modified_at TIMESTAMP,

       UNIQUE(eater_group_id, restaurant_id),

       CONSTRAINT fk_restaurant_audiences__restaurant
         FOREIGN KEY(restaurant_id)
           REFERENCES restaurants(id),

       CONSTRAINT fk_restaurant_audiences__eater_groups
         FOREIGN KEY(eater_group_id)
           REFERENCES eater_groups(id)
);

--;;

CREATE VIEW full_eater_memberships AS
SELECT
       g.id as eater_group_id,
       g.created_at as group_created_at,
       g.modified_at as group_modified_at,
       g.display_name,
       g.owner_id,
       egm.id as membership_id,
       egm.email,
       egm.alias,
       egm.created_at as membership_created_at
FROM eater_groups g LEFT JOIN
     eater_group_memberships egm ON g.id = egm.group_id
