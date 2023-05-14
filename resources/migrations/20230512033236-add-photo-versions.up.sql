
CREATE TABLE photos (
       id          UUID NOT NULL PRIMARY KEY,
       photo_title VARCHAR,
       created_at  TIMESTAMP,
       modified_at TIMESTAMP,
       hostname    VARCHAR
);

--;;

CREATE TABLE albums(
       id             UUID NOT NULL PRIMARY KEY,
       album_name     VARCHAR,
       created_at     TIMESTAMP,
       modified_at    TIMESTAMP,
       description    VARCHAR,
       cover_photo_id UUID
);

--;;

CREATE TABLE photos_in_albums(
       id          UUID NOT NULL PRIMARY KEY,
       photo_id    UUID NOT NULL,
       created_at  TIMESTAMP,
       modified_at TIMESTAMP,
       album_id    UUID NOT NULL,
       CONSTRAINT fk_photo
                  FOREIGN KEY (photo_id)
                  REFERENCES  photos(id),
       CONSTRAINT fk_album
                  FOREIGN KEY (album_id)
                  REFERENCES  albums(id)
);

--;;

CREATE TABLE photo_versions (
       id                UUID NOT NULL PRIMARY KEY,
       photo_id          UUID NOT NULL,
       path              VARCHAR NOT NULL,
       filename          VARCHAR NOT NULL,
       storage_driver    VARCHAR NOT NULL,
       storage_root      VARCHAR NOT NULL,
       image_category    VARCHAR,
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       CONSTRAINT fk_photo_versions_photo
                  FOREIGN KEY (photo_id)
                  REFERENCES  photos(id)
);

--;;

CREATE OR REPLACE VIEW album_contents AS
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
     JOIN albums a ON a.id = pia.album_id
     LEFT JOIN photos p2 ON p2.id = a.cover_photo_id;

--;;

CREATE OR REPLACE VIEW enhanced_albums AS
SELECT
    a.*,
    p2.photo_title AS cover_photo_title
FROM albums a LEFT JOIN photos p2 ON p2.id = a.cover_photo_id;

--;;

CREATE OR REPLACE VIEW full_photos AS
SELECT p.*,
pv.id as photo_version_id,
pv.path,
pv.photo_id,
pv.filename,
pv.image_category,
pv.storage_driver,
pv.storage_root
FROM photos p LEFT JOIN photo_versions pv ON pv.photo_id = p.id
