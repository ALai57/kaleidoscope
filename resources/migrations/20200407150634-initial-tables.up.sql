
CREATE TABLE users (
       id uuid not null primary key,
       first_name varchar(32),
       last_name varchar(32),
       username varchar(32) not null unique,
       email varchar not null unique
);

--;;

CREATE TABLE logins (
       id uuid not null,
       hashed_password text not null,
       foreign key (id) references users(id)
);

--;;

CREATE TABLE permissions (
       id serial primary key,
       title text not null,
       description text not null,
       active boolean not null default true
);

--;;

CREATE TABLE roles (
       id serial primary key,
       title text not null unique,
       description text not null,
       active boolean not null default true
);

--;;

CREATE TABLE roles_permissions (
       role_id integer,
       permission_id integer,
       primary key (role_id, permission_id),
       constraint fk_rp_role foreign key (role_id) references roles(id),
       constraint fk_rp_permission foreign key (permission_id) references permissions(id)
);

--;;

ALTER TABLE users ADD COLUMN role_id integer NOT NULL, ADD CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES roles(id);

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
       id INT,
       name text,
       url text,
       image_url text,
       description text
);

--;;

ALTER TABLE organizations ADD CONSTRAINT unique_organizations UNIQUE (name);

--;;

CREATE TABLE IF NOT EXISTS projects(
       id INT,
       name text,
       url text,
       image_url text,
       description text,
       organization_names text[],
       skills_names JSONB[]
);

--;;

ALTER TABLE projects ADD CONSTRAINT unique_projects UNIQUE (name);

--;;

CREATE TABLE IF NOT EXISTS skills(
       id INT,
       name text,
       url text,
       image_url text,
       description text,
       skill_category text
);

--;;

ALTER TABLE skills ADD CONSTRAINT unique_skills UNIQUE (name);

--;;

CREATE TABLE articles(
       title VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp TIMESTAMP,
       author VARCHAR (50),
       article_url VARCHAR (100),
       article_id SERIAL PRIMARY KEY
);

--;;

CREATE TABLE content(
       article_id INT,
       content text,
       dynamicjs text[]
);
