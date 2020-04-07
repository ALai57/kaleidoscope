CREATE TABLE users (id uuid not null primary key, first_name varchar(32), last_name varchar(32), username varchar(32) not null unique, email varchar not null unique);
