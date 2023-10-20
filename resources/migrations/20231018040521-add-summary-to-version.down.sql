ALTER TABLE articles DROP COLUMN summary;

--;;

DROP VIEW IF EXISTS published_articles;

--;;

DROP VIEW IF EXISTS full_versions;

--;;

DROP VIEW IF EXISTS full_branches;

--;;

CREATE OR REPLACE VIEW full_versions AS
SELECT
    av.id AS version_id,
    av.content,
    av.created_at,
    av.modified_at,
    ab.id AS branch_id,
    ab.branch_name,
    ab.published_at,
    ab.created_at AS branch_created_at,
    ab.modified_at AS branch_modified_at,
    a.id AS article_id,
    a.author,
    a.article_tags,
    a.article_title,
    a.article_url,
    a.hostname,
    a.created_at AS article_created_at,
    a.modified_at AS article_modified_at
FROM article_versions av
     JOIN article_branches ab ON ab.id = av.branch_id
     JOIN articles a ON a.id = ab.article_id

--;;

CREATE OR REPLACE VIEW full_branches AS
SELECT
    ab.id AS branch_id,
    ab.branch_name,
    ab.published_at,
    ab.created_at,
    ab.modified_at,
    a.id AS article_id,
    a.author,
    a.article_tags,
    a.article_title,
    a.article_url,
    a.hostname,
    a.created_at AS article_created_at,
    a.modified_at AS article_modified_at
FROM article_branches ab
     JOIN articles a ON a.id = ab.article_id

--;;

CREATE OR REPLACE VIEW published_articles AS
SELECT *
FROM (SELECT *, RANK() OVER (PARTITION BY article_id ORDER BY published_at DESC, created_at DESC) AS rank
     FROM full_versions
     WHERE published_at IS NOT NULL) AS sq
WHERE rank = 1
