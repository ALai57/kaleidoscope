
CREATE TABLE users (
       id         UUID NOT NULL PRIMARY KEY,
       first_name VARCHAR(32),
       last_name  VARCHAR(32),
       username   VARCHAR(32) NOT NULL UNIQUE,
       avatar     BYTEA,
       email      VARCHAR NOT NULL UNIQUE
);

--;;

CREATE TABLE organizations(
       id          BIGSERIAL PRIMARY KEY,
       name        VARCHAR UNIQUE,
       url         VARCHAR,
       image_url   VARCHAR,
       description VARCHAR
);

--;;

CREATE TABLE projects(
       id          BIGSERIAL PRIMARY KEY,
       name        VARCHAR UNIQUE,
       url         VARCHAR,
       image_url   VARCHAR,
       description VARCHAR
);

--;;

CREATE TABLE skills(
       id             BIGSERIAL PRIMARY KEY,
       name           VARCHAR UNIQUE,
       url            VARCHAR,
       image_url      VARCHAR,
       description    VARCHAR,
       skill_category VARCHAR
);

--;;

CREATE TABLE articles(
       id           BIGSERIAL PRIMARY KEY,
       title        VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp    TIMESTAMP,
       author       VARCHAR (50),
       article_url  VARCHAR (100),
       content      VARCHAR
);

--;;

CREATE TABLE projects_organizations(
       id              BIGSERIAL PRIMARY KEY,
       project_id      INTEGER,
       organization_id INTEGER
);

--;;

CREATE TABLE projects_skills(
       id          BIGSERIAL PRIMARY KEY,
       project_id  INTEGER,
       skills_id   INTEGER,
       description VARCHAR
);
