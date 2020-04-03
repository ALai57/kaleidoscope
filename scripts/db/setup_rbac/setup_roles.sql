CREATE TABLE roles (id serial primary key, title text not null, description text not null, active boolean not null default true);
