ALTER TABLE article_audiences DROP CONSTRAINT article_audiences_article_hostname_fk;

--;;

ALTER TABLE article_audiences DROP COLUMN hostname;

--;;

ALTER TABLE article_tags DROP CONSTRAINT article_tags_tag_hostname_fk;

--;;

ALTER TABLE article_tags DROP CONSTRAINT article_tags_article_hostname_fk;

--;;

ALTER TABLE article_tags DROP COLUMN hostname;

--;;

ALTER TABLE article_versions DROP CONSTRAINT article_versions_branch_hostname_fk;

--;;

ALTER TABLE article_versions DROP COLUMN hostname;

--;;

ALTER TABLE article_branches DROP CONSTRAINT article_branches_article_hostname_fk;

--;;

ALTER TABLE article_branches DROP CONSTRAINT article_branches_id_hostname_unique;

--;;

ALTER TABLE article_branches DROP COLUMN hostname;

--;;

ALTER TABLE tags DROP CONSTRAINT tags_id_hostname_unique;

--;;

ALTER TABLE tags ALTER COLUMN hostname DROP NOT NULL;

--;;

ALTER TABLE articles DROP CONSTRAINT articles_id_hostname_unique;

--;;

ALTER TABLE articles ALTER COLUMN hostname DROP NOT NULL;
