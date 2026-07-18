-- Albums had no tenancy: GET /albums returned every site's albums. Give albums
-- a hostname like the rest of the CMS. Existing rows predate multi-tenancy and
-- belong to the original site; backfill them before enforcing NOT NULL.
ALTER TABLE albums ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE albums SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE albums ALTER COLUMN hostname SET NOT NULL;

--;;

-- Composite-unique target so child tables (photos_in_albums) can carry a
-- FOREIGN KEY (album_id, hostname) and the DB forbids cross-tenant attachment.
ALTER TABLE albums ADD CONSTRAINT albums_id_hostname_unique UNIQUE (id, hostname);

--;;

-- Regenerate the album views to surface hostname. DROP + CREATE (not CREATE OR
-- REPLACE): Postgres forbids inserting a column into the middle of a view.
DROP VIEW IF EXISTS album_contents;

--;;

DROP VIEW IF EXISTS enhanced_albums;

--;;

CREATE VIEW enhanced_albums AS
SELECT
    a.*,
    p2.photo_title AS cover_photo_title
FROM albums a LEFT JOIN photos p2 ON p2.id = a.cover_photo_id;

--;;

CREATE VIEW album_contents AS
SELECT
    pia.id          AS album_content_id,
    a.id            AS album_id,
    p.id            AS photo_id,
    pia.modified_at AS added_to_album_at,
    p.photo_title,
    a.album_name,
    a.description   AS album_description,
    a.cover_photo_id,
    a.hostname
FROM photos_in_albums pia
     JOIN photos p ON p.id = pia.photo_id
     JOIN albums a ON a.id = pia.album_id;
