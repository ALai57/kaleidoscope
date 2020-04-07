CREATE TABLE roles (id serial primary key, title text not null unique, description text not null, active boolean not null default true);
