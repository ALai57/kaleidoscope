-- Harden the article family to the recipes gold-standard: the DB itself forbids
-- attaching a child (branch/version/tag/audience) to a parent on a DIFFERENT
-- tenant. Until now that was only enforced in application code (the class of the
-- cross-site-branch bug). Composite (id, hostname) FKs make it structural.
--
-- The original single-column FKs are kept (they were declared inline and are
-- auto-named, so not portably droppable); the composite FKs are additive and
-- add the hostname-match guarantee.

-- Parents: enforce hostname + expose a composite-unique target.
UPDATE articles SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE articles ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE articles ADD CONSTRAINT articles_id_hostname_unique UNIQUE (id, hostname);

--;;

UPDATE tags SET hostname = 'andrewslai.com' WHERE hostname IS NULL;

--;;

ALTER TABLE tags ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE tags ADD CONSTRAINT tags_id_hostname_unique UNIQUE (id, hostname);

--;;

-- article_branches -> articles
ALTER TABLE article_branches ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE article_branches SET hostname =
  (SELECT a.hostname FROM articles a WHERE a.id = article_branches.article_id)
  WHERE hostname IS NULL;

--;;

ALTER TABLE article_branches ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE article_branches ADD CONSTRAINT article_branches_id_hostname_unique UNIQUE (id, hostname);

--;;

ALTER TABLE article_branches ADD CONSTRAINT article_branches_article_hostname_fk
  FOREIGN KEY (article_id, hostname) REFERENCES articles (id, hostname);

--;;

-- article_versions -> article_branches
ALTER TABLE article_versions ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE article_versions SET hostname =
  (SELECT ab.hostname FROM article_branches ab WHERE ab.id = article_versions.branch_id)
  WHERE hostname IS NULL;

--;;

ALTER TABLE article_versions ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE article_versions ADD CONSTRAINT article_versions_branch_hostname_fk
  FOREIGN KEY (branch_id, hostname) REFERENCES article_branches (id, hostname);

--;;

-- article_tags -> articles + tags
ALTER TABLE article_tags ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE article_tags SET hostname =
  (SELECT a.hostname FROM articles a WHERE a.id = article_tags.article_id)
  WHERE hostname IS NULL;

--;;

ALTER TABLE article_tags ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE article_tags ADD CONSTRAINT article_tags_article_hostname_fk
  FOREIGN KEY (article_id, hostname) REFERENCES articles (id, hostname);

--;;

ALTER TABLE article_tags ADD CONSTRAINT article_tags_tag_hostname_fk
  FOREIGN KEY (tag_id, hostname) REFERENCES tags (id, hostname);

--;;

-- article_audiences -> articles
ALTER TABLE article_audiences ADD COLUMN IF NOT EXISTS hostname VARCHAR;

--;;

UPDATE article_audiences SET hostname =
  (SELECT a.hostname FROM articles a WHERE a.id = article_audiences.article_id)
  WHERE hostname IS NULL;

--;;

ALTER TABLE article_audiences ALTER COLUMN hostname SET NOT NULL;

--;;

ALTER TABLE article_audiences ADD CONSTRAINT article_audiences_article_hostname_fk
  FOREIGN KEY (article_id, hostname) REFERENCES articles (id, hostname);
