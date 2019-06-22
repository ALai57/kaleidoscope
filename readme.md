
This is a template for a Clojure backend and Clojurescript frontend.
The app must run on Java 11.


The template includes:
- Server with handler
- Basic template for sortable react table
- Postgres backend
- Single page app with navigation between screens


*Servers and development environment*

How to start server:

```lein run```

How to start front end developing with hot reloading:

```lein figwheel <buildname here>```

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

*** Must have NPM installed to manage JS dependencies.

On Ubuntu::
```
sudo apt-get update
sudo apt-get install npm
```

*** Editor configuration things

CIDER
```
(defun cider-jack-in-with-profile ()
  (interactive)
  (letrec ((profile (read-string "Enter profile name: "))
           (lein-params (concat "with-profile +" profile " repl :headless")))
    (message "lein-params set to: %s" lein-params)
    (set-variable 'cider-lein-parameters lein-params)
    ;; just a empty parameter
    (cider-jack-in '())))
```

EMACS AND REPL/FIGWHEEL
```
(use 'figwheel-sidecar.repl-api)
(start-figwheel! "todomvc")
(cljs-repl "todomvc")
```
