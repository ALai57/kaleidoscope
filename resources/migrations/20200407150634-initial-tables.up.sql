
CREATE TABLE articles(
       id                BIGSERIAL PRIMARY KEY,
       author            VARCHAR (50),
       article_url       VARCHAR (100) UNIQUE,
       article_tags      VARCHAR (32),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP
);

--;;

CREATE TABLE article_branches(
       id                BIGSERIAL PRIMARY KEY,
       article_id        INT NOT NULL,
       published_at      TIMESTAMP,
       branch_name       VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       UNIQUE(branch_name, article_id),

       CONSTRAINT fk_articles
         FOREIGN KEY(article_id)
           REFERENCES articles(id)

);

--;;

CREATE TABLE article_versions(
       id                BIGSERIAL PRIMARY KEY,
       branch_id         INT NOT NULL,
       title             VARCHAR,
       content           VARCHAR,
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       CONSTRAINT fk_branches
         FOREIGN KEY(branch_id)
           REFERENCES article_branches(id)
);

--;;

CREATE TABLE portfolio_entries(
       id          BIGSERIAL PRIMARY KEY,
       name        VARCHAR UNIQUE,
       type        VARCHAR,
       url         VARCHAR,
       image_url   VARCHAR,
       description VARCHAR,
       tags        VARCHAR
);

--;;

CREATE TABLE portfolio_links(
       id          BIGSERIAL PRIMARY KEY,
       name_1      VARCHAR,
       relation    VARCHAR,
       name_2      VARCHAR,
       description VARCHAR
);

--;;

CREATE OR REPLACE VIEW full_versions AS
SELECT
    av.id AS version_id,
    av.title,
    av.content,
    av.created_at,
    av.modified_at,
    ab.id AS branch_id,
    ab.branch_name,
    ab.published_at,
    a.id AS article_id,
    a.author,
    a.article_url,
    a.article_tags
FROM article_versions av
     JOIN article_branches ab ON ab.id = av.branch_id
     JOIN articles a ON a.id = ab.article_id

--;;

CREATE OR REPLACE VIEW full_branches AS
SELECT
    ab.id AS branch_id,
    ab.branch_name,
    ab.published_at,
    a.id AS article_id,
    a.author,
    a.article_url,
    a.article_tags
FROM article_branches ab
     JOIN articles a ON a.id = ab.article_id

--;;

CREATE OR REPLACE VIEW published_articles AS
SELECT *
FROM (SELECT *, RANK() OVER (PARTITION BY article_id ORDER BY published_at DESC, created_at DESC) AS rank
     FROM full_versions
     WHERE published_at IS NOT NULL) AS sq
WHERE rank = 1

--;;
