
CREATE TABLE users (
       id uuid    NOT NULL PRIMARY KEY,
       first_name VARCHAR(32),
       last_name  VARCHAR(32),
       username   VARCHAR(32) NOT NULL UNIQUE,
       avatar     BYTEA,
       email      VARCHAR NOT NULL UNIQUE,
       role_id    INTEGER NOT NULL,
);

--;;

CREATE TABLE logins (
       id uuid         NOT NULL,
       hashed_password VARCHAR NOT NULL,

       FOREIGN KEY (id) REFERENCES users(id)
);

--;;

CREATE TABLE organizations(
       id          INTEGER,
       name        VARCHAR UNIQUE,
       url         VARCHAR,
       image_url   VARCHAR,
       description VARCHAR
);

--;;

CREATE TABLE projects(
       id          INTEGER,
       name        VARCHAR UNIQUE,
       url         VARCHAR,
       image_url   VARCHAR,
       description VARCHAR
);

--;;

CREATE TABLE skills(
       id             INTEGER,
       name           VARCHAR UNIQUE,
       url            VARCHAR,
       image_url      VARCHAR,
       description    VARCHAR,
       skill_category VARCHAR
);

--;;

CREATE TABLE articles(
       title        VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp    TIMESTAMP,
       author       VARCHAR (50),
       article_url  VARCHAR (100),
       content      VARCHAR,
       article_id   SERIAL PRIMARY KEY
);

--;;

CREATE TABLE projects_organizations(
       project_id      INTEGER,
       organization_id INTEGER
);

--;;

CREATE TABLE projects_skills(
       project_id  INTEGER,
       skills_id   INTEGER,
       description VARCHAR
);
