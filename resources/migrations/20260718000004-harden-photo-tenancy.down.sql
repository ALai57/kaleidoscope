ALTER TABLE photos_in_albums DROP CONSTRAINT photos_in_albums_album_hostname_fk;

--;;

ALTER TABLE photos_in_albums DROP CONSTRAINT photos_in_albums_photo_hostname_fk;

--;;

ALTER TABLE photos_in_albums DROP COLUMN hostname;

--;;

ALTER TABLE photo_versions DROP CONSTRAINT photo_versions_photo_hostname_fk;

--;;

ALTER TABLE photo_versions DROP COLUMN hostname;

--;;

ALTER TABLE photos DROP CONSTRAINT photos_id_hostname_unique;

--;;

ALTER TABLE photos ALTER COLUMN hostname DROP NOT NULL;
