-- The full_photos view does not reference content_hash, so a bare drop is safe.
ALTER TABLE public.photo_versions DROP COLUMN IF EXISTS content_hash;
