

CREATE TABLE permissions (
       id          SERIAL PRIMARY KEY,
       title       VARCHAR NOT NULL,
       description VARCHAR NOT NULL,
       active      BOOLEAN NOT NULL DEFAULT TRUE
);

--;;

CREATE TABLE roles (
       id          SERIAL PRIMARY KEY,
       title       VARCHAR NOT NULL,
       description VARCHAR NOT NULL,
       active      BOOLEAN NOT NULL DEFAULT TRUE
);

--;;

CREATE TABLE roles_permissions (
       role_id       INTEGER,
       permission_id INTEGER,

       PRIMARY KEY (role_id, permission_id),
       CONSTRAINT fk_rp_role       FOREIGN KEY (role_id)       REFERENCES roles(id),
       CONSTRAINT fk_rp_permission FOREIGN KEY (permission_id) REFERENCES permissions(id)
);

--;;

CREATE TABLE users (
       id uuid    NOT NULL PRIMARY KEY,
       first_name VARCHAR(32),
       last_name  VARCHAR(32),
       username   VARCHAR(32) NOT NULL UNIQUE,
       avatar     BYTEA,
       email      VARCHAR NOT NULL UNIQUE,
       role_id    INTEGER NOT NULL,
       CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES roles(id)
);

--;;

CREATE TABLE logins (
       id uuid         NOT NULL,
       hashed_password VARCHAR NOT NULL,

       FOREIGN KEY (id) REFERENCES users(id)
);

--;;

INSERT INTO permissions (title, description, active) VALUES
       ('read:articles', 'Can read all articles', true),
       ('write:articles', 'Can write articles', true),
       ('edit:articles', 'Can access editing GUI', true);

--;;

INSERT INTO roles (title, description, active) VALUES
       ('admin', 'Has all administrator privileges', true),
       ('read_only', 'Does not have admin privileges', true);

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
