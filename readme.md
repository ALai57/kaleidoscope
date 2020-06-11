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

# Deployment

CREATE ELASTIC BEANSTALK ENVIRONMENT
```
aws elasticbeanstalk create-environment \
    --application-name andrewslai \
    --environment-name staging \
    --solution-stack-name "64bit Amazon Linux 2018.03 v2.12.14 running Docker 18.06.1-ce" \
    --region us-east-1
    --option-settings file://eb-default-vpc.json
```

MODIFY RESUME CARDS HORRIBLE HACK:
`cider-jack-in-with-profile upload`
in the articles.clj namespace, look at comment at bottom of file
use this to update the database - but be careful! It overwrites
existing DB and repopulates from scratch.

TERRAFORM
`terraform plan -var-file=andrewslai_secrets.tfvars`


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

ELASTIC BEANSTALK
```
eb platform select
eb platform show
eb ssh staging
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


