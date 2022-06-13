
CREATE TABLE photos (
       id         UUID NOT NULL PRIMARY KEY,
       photo_name VARCHAR NOT NULL UNIQUE,
       created_at TIMESTAMP,
       modified_at TIMESTAMP
);

--;;

CREATE TABLE albums(
       id         UUID NOT NULL PRIMARY KEY,
       album_name VARCHAR,
       created_at TIMESTAMP,
       modified_at TIMESTAMP
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

CREATE VIEW photo_albums AS
SELECT
    pia.modified_at AS added_to_album_at,
    p.photo_name,
    a.album_name
FROM photos_in_albums pia
     JOIN photos p ON p.id = pia.photo_id
     JOIN albums a ON a.id = pia.album_id
