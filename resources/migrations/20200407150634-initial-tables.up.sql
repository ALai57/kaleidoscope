

CREATE TABLE permissions (
       id serial primary key,
       title text not null,
       description text not null,
       active boolean not null default true
);

--;;

CREATE TABLE roles (
       id serial primary key,
       title text not null,
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

CREATE TABLE users (
       id uuid not null primary key,
       first_name varchar(32),
       last_name varchar(32),
       username varchar(32) not null unique,
       avatar bytea,
       email varchar not null unique,
       role_id integer not null,
       CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES roles(id)
);

--;;

CREATE TABLE logins (
       id uuid not null,
       hashed_password text not null,
       foreign key (id) references users(id)
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
       id integer,
       name varchar unique,
       url text,
       image_url text,
       description text
);

--;;

CREATE TABLE projects(
       id integer,
       name varchar unique,
       url varchar,
       image_url varchar,
       description varchar
);

--;;

CREATE TABLE skills(
       id integer,
       name varchar unique,
       url text,
       image_url text,
       description text,
       skill_category text
);

--;;

CREATE TABLE articles(
       title VARCHAR (100),
       article_tags VARCHAR (32),
       timestamp TIMESTAMP,
       author VARCHAR (50),
       article_url VARCHAR (100),
       content text,
       article_id SERIAL PRIMARY KEY
);

--;;

CREATE TABLE projects_organizations(
       project_id integer,
       organization_id integer
);

--;;

CREATE TABLE projects_skills(
       project_id integer,
       skills_id integer,
       description varchar
);
