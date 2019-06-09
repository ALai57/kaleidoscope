
This is a template for a Clojure backend and Clojurescript frontend.
The app must run on Java 11.


The template includes:
- Server with handler
- Basic template for sortable react table
- Postgres backend (not validated yet)


*Servers and development environment*

How to start server:

```lein run```

How to start front end developing with hot reloading:

```lein figwheel```

*How to setup postgres:*

Requirements for postgres backend:

psql -> database -> user with password and access to database


run postgres in super user

```sudo su postgres```

run postgres

```
   * create database full-stack-template-postgres-db*
   psql -d full-stack-template-postgres-db
   CREATE USER db_user WITH ENCRYPTED PASSWORD 'password';
   GRANT ALL ON DATABASE full-stack-template-postgres-db TO db_user;
   GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO db_user;

```

Helpful psql commands:

```
   \du :: list users
   \dt :: List tables
   select current_user;
   select current_database();
   select schema_name from information_schema.schemata;
```

Testing after rename
