DROP TABLE IF EXISTS public.curation_feedback;
--;;
DROP TABLE IF EXISTS public.curation_stage_runs;
--;;
DROP TABLE IF EXISTS public.curation_run_candidates;
--;;
ALTER TABLE public.recommendations DROP COLUMN IF EXISTS vetting;
