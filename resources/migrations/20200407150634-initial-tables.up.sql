
CREATE TABLE users (
       id         UUID NOT NULL PRIMARY KEY,
       first_name VARCHAR(32),
       last_name  VARCHAR(32),
       username   VARCHAR(32) NOT NULL UNIQUE,
       avatar     BYTEA,
       email      VARCHAR NOT NULL UNIQUE
);

--;;

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
       article_id        INT,
       published_at      TIMESTAMP,
       branch_name       VARCHAR (100),
       created_at        TIMESTAMP,
       modified_at       TIMESTAMP,

       CONSTRAINT fk_articles
         FOREIGN KEY(article_id)
           REFERENCES articles(id)

);

--;;

CREATE TABLE article_versions(
       id                BIGSERIAL PRIMARY KEY,
       branch_id         INT,
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
