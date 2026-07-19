-- Reversible + lossless: the dropped columns are derivable. storage_root was
-- the tenant bucket (= hostname); storage_driver was always 's3'. Re-add,
-- backfill from those facts, restore NOT NULL, and recreate the view with them.
DROP VIEW IF EXISTS public.full_photos;
--;;
ALTER TABLE public.photo_versions ADD COLUMN IF NOT EXISTS storage_driver character varying;
--;;
ALTER TABLE public.photo_versions ADD COLUMN IF NOT EXISTS storage_root character varying;
--;;
UPDATE public.photo_versions SET storage_driver = 's3' WHERE storage_driver IS NULL;
--;;
UPDATE public.photo_versions SET storage_root = hostname WHERE storage_root IS NULL;
--;;
ALTER TABLE public.photo_versions ALTER COLUMN storage_driver SET NOT NULL;
--;;
ALTER TABLE public.photo_versions ALTER COLUMN storage_root SET NOT NULL;
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
    pv.image_category,
    pv.storage_driver,
    pv.storage_root
   FROM (public.photos p
     LEFT JOIN public.photo_versions pv ON ((pv.photo_id = p.id)));
