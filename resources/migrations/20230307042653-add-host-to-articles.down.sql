
ALTER TABLE articles DROP COLUMN hostname;

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
    a.title,
    a.article_url,
    a.article_tags,
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
    a.article_url,
    a.article_tags,
    a.created_at AS article_created_at,
    a.modified_at AS article_modified_at
FROM article_branches ab
     JOIN articles a ON a.id = ab.article_id
