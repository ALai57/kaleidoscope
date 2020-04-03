CREATE TABLE logins (id uuid not null, hashed_password text not null, foreign key (id) references users(id));
