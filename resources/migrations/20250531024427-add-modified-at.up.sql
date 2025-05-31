
ALTER TABLE user_group_memberships ADD COLUMN modified_at TIMESTAMP;

--;;

ALTER TABLE article_audiences ADD COLUMN modified_at TIMESTAMP;

--;;

DROP VIEW IF EXISTS full_memberships;

--;;

DROP VIEW IF EXISTS full_article_audiences;

--;;

ALTER TABLE article_audiences
DROP CONSTRAINT fk_article_audiences__groups;

--;;

ALTER TABLE article_audiences
ALTER COLUMN group_id TYPE uuid USING group_id::uuid;

--;;

ALTER TABLE groups
ALTER COLUMN id TYPE uuid USING id::uuid;

--;;

ALTER TABLE user_group_memberships
ALTER COLUMN id TYPE uuid USING id::uuid;

--;;

ALTER TABLE user_group_memberships
ALTER COLUMN group_id TYPE uuid USING group_id::uuid;

--;;

ALTER TABLE article_audiences
ADD CONSTRAINT fk_article_audiences__groups
    FOREIGN KEY(group_id)
        REFERENCES groups(id);

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
     user_group_memberships ugm ON g.id = ugm.group_id

--;;

CREATE OR REPLACE VIEW full_article_audiences AS
SELECT aa.*,
       a.hostname,
       a.public_visibility
FROM article_audiences aa INNER JOIN articles a on aa.article_id = a.id
