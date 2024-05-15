
ALTER TABLE photos ADD description VARCHAR

--;;

DROP VIEW full_photos

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
