-- A per-object checksum, populated for NEW uploads only (no corpus backfill).
-- 'sha256:' + 64 hex chars = 71. Nullable: the pre-Phase-3 corpus keeps NULL,
-- and reconciliation (tasks/reconcile.clj) verifies stored bytes against this
-- for every row that has one, skipping rows that don't. NOT content-addressing
-- — the key stays the intrinsic UUID path; this is a checksum we verify.
ALTER TABLE public.photo_versions ADD COLUMN IF NOT EXISTS content_hash character varying(71);
