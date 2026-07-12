-- Recipes: scrape/edit/search/share domain. See plans/2026-07-10-recipes-feature/PLAN.md.
--
-- Identity is a single opaque UUID; recipe_url is the address (slug), not a
-- second identity. Recipe content is one JSONB value shape: {title, sections
-- [{name?, ingredients [string], steps [string]}], servings?, times?} (see
-- plans/2026-07-11-recipe-sections/DESIGN.md); `content` is the current recipe
-- and `original_content` is the immutable scrape — same shape, cannot drift.
CREATE TABLE recipes (
  id                UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  recipe_url        VARCHAR NOT NULL,          -- address (slug)
  hostname          VARCHAR NOT NULL,          -- tenant
  content           JSONB NOT NULL,            -- {title, sections[{name?, ingredients[], steps[]}], servings?, prep_time_minutes?, cook_time_minutes?}
  original_content  JSONB,                     -- immutable scrape; same shape as `content`
  source_url        VARCHAR,
  author            VARCHAR,
  public_visibility BOOLEAN NOT NULL DEFAULT FALSE,
  created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  modified_at       TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (recipe_url, hostname),
  UNIQUE (id, hostname)                        -- FK target for child tables' composite (id, hostname) FKs
);

--;;

CREATE TABLE recipe_label_groups (             -- e.g. "ethnicity"
  id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  name       VARCHAR NOT NULL,
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (name, hostname),
  UNIQUE (id, hostname)
);

--;;

CREATE TABLE recipe_labels (                   -- e.g. "indian" (group=ethnicity) or "baking" (no group)
  id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  name       VARCHAR NOT NULL,
  group_id   UUID,                             -- NULL = ungrouped
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (name, group_id, hostname),
  UNIQUE (id, hostname),
  -- label and its group share a tenant (composite FK); not enforced when group_id is NULL
  FOREIGN KEY (group_id, hostname) REFERENCES recipe_label_groups (id, hostname) ON DELETE CASCADE
);

--;;

CREATE TABLE recipe_label_assignments (        -- recipe <-> label join
  id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  recipe_id  UUID NOT NULL,
  label_id   UUID NOT NULL,
  group_id   UUID,                             -- denormalized from the label so the invariant lives on THIS row
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (recipe_id, label_id),
  -- One label per group per recipe. Relies on SQL NULL-distinct semantics:
  -- many (recipe_id, NULL) rows are allowed (ungrouped labels), but only one
  -- row per (recipe_id, non-null group_id). Portable to H2 and Postgres,
  -- unlike a partial unique index.
  UNIQUE (recipe_id, group_id),
  -- recipe/label and this assignment share a tenant (composite FKs)
  FOREIGN KEY (recipe_id, hostname) REFERENCES recipes (id, hostname)       ON DELETE CASCADE,
  FOREIGN KEY (label_id, hostname)  REFERENCES recipe_labels (id, hostname) ON DELETE CASCADE
);

--;;

CREATE TABLE recipe_audiences (                -- who a recipe is shared with
  id         UUID DEFAULT gen_random_uuid() PRIMARY KEY,
  group_id   VARCHAR NOT NULL,  -- VARCHAR(36) matches legacy groups(id)
  recipe_id  UUID NOT NULL,
  hostname   VARCHAR NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
  UNIQUE (group_id, recipe_id),
  FOREIGN KEY (recipe_id, hostname) REFERENCES recipes (id, hostname) ON DELETE CASCADE
);

--;;

CREATE INDEX idx_recipes_hostname ON recipes (hostname);
--;;
CREATE INDEX idx_recipe_label_assignments_recipe_id ON recipe_label_assignments (recipe_id);
--;;
CREATE INDEX idx_recipe_audiences_recipe_id ON recipe_audiences (recipe_id);
