ALTER TABLE users ADD COLUMN role_id integer NOT NULL,ADD CONSTRAINT fk_user_role FOREIGN KEY (role_id) REFERENCES roles(id);
