-- Personal recommender: interests (taste profiles) + recommendations (shelves).
-- See plans/2026-07-12-personal-recommender/DESIGN.md.
--
-- Interest ≈ Project made literal: each interest is backed by a projects row
-- because curation runs reuse project_workflow_runs, whose project_id FK
-- requires one. Deleting the backing project cascades here (and from here to
-- recommendations), so one delete tears down the whole interest.
CREATE TABLE interests (
  id            UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  user_id       TEXT NOT NULL,
  project_id    UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  intent        TEXT NOT NULL,
  taste_profile JSONB NOT NULL DEFAULT '{}',  -- {keywords, formats, lengths, trusted_sources, novelty_ratio, cadence, refinements}
  created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (project_id)
);

--;;

CREATE TABLE recommendations (
  id          UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  interest_id UUID NOT NULL REFERENCES interests(id) ON DELETE CASCADE,
  kind        TEXT NOT NULL,                   -- podcast/article/show/video/book/paper/newsletter/course
  title       TEXT NOT NULL,
  source      TEXT NOT NULL,                   -- e.g. "PBS Frontline"
  url         TEXT,
  est_time    TEXT,                            -- e.g. "18 min", "6 episodes"
  why         TEXT NOT NULL,                   -- one-line rationale surfaced on the card
  origin      TEXT NOT NULL DEFAULT 'novel',   -- trusted | novel (drives the "new source" tag)
  status      TEXT NOT NULL DEFAULT 'shelved', -- shelved | queued | archived
  added_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now()
);

--;;

CREATE INDEX idx_interests_user_id ON interests (user_id);
--;;
CREATE INDEX idx_recommendations_interest_id ON recommendations (interest_id);
