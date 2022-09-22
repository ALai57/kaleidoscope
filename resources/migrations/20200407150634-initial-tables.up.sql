
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
       id           BIGSERIAL PRIMARY KEY,
       title        VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp    TIMESTAMP,
       author       VARCHAR (50),
       article_url  VARCHAR (100) UNIQUE,
       content      VARCHAR
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
