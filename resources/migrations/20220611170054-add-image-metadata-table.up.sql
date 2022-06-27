
CREATE TABLE photos (
       id          UUID NOT NULL PRIMARY KEY,
       photo_title VARCHAR,
       photo_src   VARCHAR NOT NULL UNIQUE,
       created_at  TIMESTAMP,
       modified_at TIMESTAMP
);

--;;

CREATE TABLE albums(
       id         UUID NOT NULL PRIMARY KEY,
       album_name VARCHAR,
       created_at TIMESTAMP,
       modified_at TIMESTAMP,
       description VARCHAR,
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

CREATE OR REPLACE VIEW photo_albums AS
SELECT
    pia.modified_at AS added_to_album_at,
    p.photo_src,
    p.photo_title,
    a.album_name,
    a.description AS album_description,
    a.cover_photo_id,
    p2.photo_src AS cover_photo_src
FROM photos_in_albums pia
     JOIN photos p ON p.id = pia.photo_id
     JOIN albums a ON a.id = pia.album_id
     LEFT JOIN photos p2 ON p2.id = a.cover_photo_id;

--;;

CREATE OR REPLACE VIEW enhanced_albums AS
SELECT
    a.album_name,
    a.description AS album_description,
    a.cover_photo_id,
    p2.photo_src AS cover_photo_src,
    p2.photo_title AS cover_photo_title
FROM albums a LEFT JOIN photos p2 ON p2.id = a.cover_photo_id;
