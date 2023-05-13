
ALTER TABLE photos ADD hostname VARCHAR;

--;;

CREATE TABLE photo_versions (
       id                UUID NOT NULL PRIMARY KEY,
       photo_id          UUID NOT NULL,
       path              VARCHAR NOT NULL,
       filename          VARCHAR NOT NULL,
       image_category    VARCHAR,
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       CONSTRAINT fk_photo_versions_photo
                  FOREIGN KEY (photo_id)
                  REFERENCES  photos(id)
);

--;;

CREATE VIEW IF NOT EXISTS full_photos AS
SELECT p.*,
pv.id as photo_version_id,
pv.photo_version_src,
pv.image_category
FROM photos p LEFT JOIN photo_versions pv ON pv.photo_id = p.id
