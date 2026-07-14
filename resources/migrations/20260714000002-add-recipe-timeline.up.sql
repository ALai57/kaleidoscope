-- Cook timeline: a durable materialized derived value computed from a recipe's
-- content + authored duration overrides. NULL until first generated. Shape:
-- {version, generator_version, generated_at, total_minutes,
--  overrides [{phase, minutes}],
--  components [{name, steps_hash, phases [{id, label, kind, steps, estimate, deps, start}]}]}
-- See plans/2026-07-14-recipe-cook-timeline/DESIGN.md.
ALTER TABLE recipes ADD COLUMN timeline JSONB;
