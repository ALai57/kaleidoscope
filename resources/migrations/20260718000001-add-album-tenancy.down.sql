DROP VIEW IF EXISTS album_contents;

--;;

DROP VIEW IF EXISTS enhanced_albums;

--;;

ALTER TABLE albums DROP CONSTRAINT albums_id_hostname_unique;

--;;

ALTER TABLE albums DROP COLUMN hostname;

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
    a.cover_photo_id
FROM photos_in_albums pia
     JOIN photos p ON p.id = pia.photo_id
     JOIN albums a ON a.id = pia.album_id;
