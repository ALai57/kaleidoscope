-- Backfill requires=[{kind: code_context_path}] on all Engineering Review steps.
--
-- The requires column was added in 20260415000001 with DEFAULT '[]', so any
-- workflow_steps rows that existed before that migration (or were seeded before
-- seed-default-workflows! detected the change) have requires = '[]'.
-- seed-default-workflows! only re-seeds when the step *count* changes, so the
-- requires field was never propagated to existing rows.

UPDATE workflow_steps
SET    requires = '[{"kind": "code_context_path"}]'
WHERE  agent_type  = 'engineering_lead'
  AND  output_kind = 'score'
  AND  requires    = '[]'::jsonb;
