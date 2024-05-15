
ALTER TABLE articles ADD public_visibility boolean DEFAULT false;

--;;

CREATE OR REPLACE VIEW full_article_audiences AS
SELECT aa.*,
       a.hostname,
       a.public_visibility
FROM article_audiences aa INNER JOIN articles a on aa.article_id = a.id
