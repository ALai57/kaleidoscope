-- Raw scrape pipeline. See plans/2026-07-12-raw-scrape-pipeline/DESIGN.md.
--
-- raw_scrapes is the immutable acquisition corpus (one row per fetch, written
-- once). processing_runs is the append-only provenance log: one row per pipeline
-- execution over a raw scrape (re-processing = a new row). recipes links to the
-- run that produced it for full lineage: recipe -> processing_run -> raw_scrape.
CREATE TABLE raw_scrapes (
  id           UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  hostname     VARCHAR NOT NULL,          -- tenant
  request_url  VARCHAR NOT NULL,          -- URL as submitted
  final_url    VARCHAR,                   -- after redirect following (NULL if never fetched)
  http_status  INT,                       -- terminal status (NULL if never fetched)
  fetch_tier   VARCHAR,                   -- 'direct' | 'firecrawl' (NULL on pre-fetch failure)
  raw_html     TEXT,                      -- captured page (NULL on pre-fetch failure)
  created_at   TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (id, hostname)                   -- composite FK target for processing_runs
);

--;;

CREATE TABLE processing_runs (
  id                UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  hostname          VARCHAR NOT NULL,     -- tenant
  raw_scrape_id     UUID NOT NULL,
  pipeline_version  VARCHAR NOT NULL,     -- build/git SHA: identity of the code that ran
  techniques        JSONB,                -- {acquire, parse, normalize} technique kinds
  facts             JSONB,                -- the ExtractedFacts artifact, incl. labels (NULL on early failure)
  content           JSONB,                -- the RecipeContent artifact (NULL on failure)
  llm_calls         JSONB,                -- [{purpose, model, request, response}]; full request stored
  warnings          JSONB,                -- [string]
  outcome           VARCHAR NOT NULL,     -- 'success' | failure reason (bot-blocked, no-recipe-found, ...)
  error_detail      JSONB,                -- {message, reason} on failure (NULL on success)
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (id, hostname),
  FOREIGN KEY (raw_scrape_id, hostname) REFERENCES raw_scrapes (id, hostname) ON DELETE CASCADE
);

--;;

-- Lineage link. Nullable because manually-created recipes have no scrape.
ALTER TABLE recipes ADD COLUMN scrape_processing_run_id UUID;

--;;

-- Single-column FK to the run's PK — NOT composite with hostname. ON DELETE SET
-- NULL must null ONLY the link column; a composite (id, hostname) FK would also
-- null recipes.hostname, which is NOT NULL, hard-failing the delete instead of
-- unlinking. Tenant match on this link is left unenforced at the DB (the run-id
-- is an opaque UUID set at scrape time) so that deleting a run never cascades
-- away a recipe — the corpus intentionally outlives recipe deletes.
ALTER TABLE recipes ADD CONSTRAINT fk_recipes_scrape_processing_run
  FOREIGN KEY (scrape_processing_run_id)
  REFERENCES processing_runs (id) ON DELETE SET NULL;

--;;

CREATE INDEX idx_processing_runs_raw_scrape_id ON processing_runs (raw_scrape_id);

--;;

CREATE INDEX idx_recipes_scrape_processing_run_id ON recipes (scrape_processing_run_id);
