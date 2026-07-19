-- Remove the mutable/deployment facts (storage_root = bucket, storage_driver)
-- from photo_versions. Location is now f(intrinsic-id, env-config): the object
-- supplies the media/<uuid>/... key, the environment supplies the bucket. The
-- full_photos VIEW depends on both columns, so it is dropped and recreated
-- without them (Postgres refuses a bare DROP COLUMN under a dependent view).
DROP VIEW IF EXISTS public.full_photos;
--;;
ALTER TABLE public.photo_versions DROP COLUMN IF EXISTS storage_driver;
--;;
ALTER TABLE public.photo_versions DROP COLUMN IF EXISTS storage_root;
--;;
CREATE VIEW public.full_photos AS
 SELECT p.id,
    p.photo_title,
    p.created_at,
    p.modified_at,
    p.hostname,
    p.description,
    pv.id AS photo_version_id,
    pv.path,
    pv.photo_id,
    pv.filename,
    pv.image_category
   FROM (public.photos p
     LEFT JOIN public.photo_versions pv ON ((pv.photo_id = p.id)));
