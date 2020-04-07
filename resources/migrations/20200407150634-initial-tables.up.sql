
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
