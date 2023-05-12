
ALTER TABLE photos ADD hostname VARCHAR;

--;;

CREATE TABLE photo_versions (
       id                UUID NOT NULL PRIMARY KEY,
       photo_id          UUID NOT NULL,
       photo_version_src VARCHAR NOT NULL,
       image_category    VARCHAR,
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       CONSTRAINT fk_photo_versions_photo
                  FOREIGN KEY (photo_id)
                  REFERENCES  photos(id)
);
