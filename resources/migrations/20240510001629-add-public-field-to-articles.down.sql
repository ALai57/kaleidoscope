
ALTER TABLE articles DROP COLUMN public_visibility

--;;

CREATE OR REPLACE VIEW full_article_audiences AS
SELECT aa.*,
       a.hostname
FROM article_audiences aa INNER JOIN articles a on aa.article_id = a.id
