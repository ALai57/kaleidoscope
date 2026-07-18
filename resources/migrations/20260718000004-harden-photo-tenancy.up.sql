-- Same recipes-style hardening for the photo family: photo_versions and
-- photos_in_albums are tenant data (via their photo) but had no hostname and no
-- schema-level guarantee they couldn't cross tenants. Add hostname + composite
-- FKs so the DB forbids attaching a version/album-link to another site's photo
-- (and album).

UPDATE photos SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE photos ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE photos ADD CONSTRAINT photos_id_hostname_unique UNIQUE (id, hostname);

--;;

-- photo_versions -> photos
ALTER TABLE photo_versions ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE photo_versions SET hostname =
  (SELECT p.hostname FROM photos p WHERE p.id = photo_versions.photo_id)
  WHERE hostname IS NULL;

--;;

ALTER TABLE photo_versions ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE photo_versions ADD CONSTRAINT photo_versions_photo_hostname_fk
  FOREIGN KEY (photo_id, hostname) REFERENCES photos (id, hostname);

--;;

-- photos_in_albums -> photos AND albums (both must share the tenant)
ALTER TABLE photos_in_albums ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE photos_in_albums SET hostname =
  (SELECT p.hostname FROM photos p WHERE p.id = photos_in_albums.photo_id)
  WHERE hostname IS NULL;

--;;

ALTER TABLE photos_in_albums ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE photos_in_albums ADD CONSTRAINT photos_in_albums_photo_hostname_fk
  FOREIGN KEY (photo_id, hostname) REFERENCES photos (id, hostname);

--;;

ALTER TABLE photos_in_albums ADD CONSTRAINT photos_in_albums_album_hostname_fk
  FOREIGN KEY (album_id, hostname) REFERENCES albums (id, hostname);
