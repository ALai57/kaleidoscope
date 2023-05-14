
ALTER TABLE photos ADD hostname VARCHAR;

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

CREATE VIEW IF NOT EXISTS full_photos AS
SELECT p.*,
pv.id as photo_version_id,
pv.path,
pv.photo_id,
pv.filename,
pv.image_category,
pv.storage_driver,
pv.storage_root
FROM photos p LEFT JOIN photo_versions pv ON pv.photo_id = p.id
