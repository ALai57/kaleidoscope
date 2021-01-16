# My personal website!  
### Also a blogging platform (in the works). 

  Backend: Dockerized Clojure, Java 11.  
Front end: Clojurescript (React/Re-frame)
  
  
### Architecture: 
- Not quite RESTful service (breaks some RESTful principles)
- Ring server, Compojure routing. 
- Postgres DB (AWS RDS). 
- Re-frame SPA


# Installation
## Clojure
The server is a standard Clojure Ring web server.
To get up and running, clone the repo and run tests with `lein test`

## Clojurescript
The Clojurescript client uses `Karma` via `lein doo` for testing, and requires
some NPM dependencies. `Karma` launches a web server which manages connected
instances of different browsers used for testing (e.g. firefox, chrome, ie6..).
The server connects to the different browsers using a websocket connection and
instructs the different browsers -- over websockets -- to run tests in an IFrame

First install NPM, e.g. `sudo apt install npm`

Install Karma test runner and chrome launcher
```
mkdir test_runner
cd test_runner
npm install karma karma-cljs-test --save-dev
npm install karma-chrome-launcher --save-dev
```

Run tests
`lein doo chrome-headless dev-test`

# Testing with Docker
First, clean the project and build an uberjar `lein do clean, uberjar`

Next, build the docker container `docker build -t andrewslai .`

If you'd like to run the server in a Docker container connected to an instance
of Postgres running on localhost, you'll need to specify that you want it to run
on the localhost network, and provide the correct environment variables for the
database.
`docker run -d --rm --network host --env-file=.env.local -p 5000:5000 andrewslai`

If you'd like to run the server in a Docker container connected to an instance
of Postgres running on an AWS database, you'll need to provide the correct
environment variables for the database.
`docker run -d --rm --env-file=.env.aws -p 5000:5000 andrewslai`

# Helpful commands

CIDER JACK IN WITH PROFILE
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
(start-figwheel! "dev")
(cljs-repl "dev")
```

DOCKER
```
docker build -t andrewslai .
docker run -d --rm --network host --env-file=.env.local -p 5000:5000 andrewslai
docker ps
docker stop 02d64e84e7c3
```

PSQL:

```
   \du :: list users
   \dt :: List tables
   select current_user;
   select current_database();
   select schema_name from information_schema.schemata;
```

POSTGRES - MODIFYING DEFAULT POSTGRES USER PASSWORD

- edit pg_hba.conf and modify to "trust". 
- stop service `service postgres stop`. 
- `service postgres start`. 
- login to psql: `sudo su - postgres`. 
```
psql
ALTER USER postgres WITH PASSWORD xxxxxxxx;
```


