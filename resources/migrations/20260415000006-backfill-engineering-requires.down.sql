-- Revert: clear requires on engineering_lead score steps
UPDATE workflow_steps
SET    requires = '[]'::jsonb
WHERE  agent_type  = 'engineering_lead'
  AND  output_kind = 'score'
  AND  requires    = '[{"kind": "code_context_path"}]'::jsonb;
