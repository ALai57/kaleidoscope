
This is my personal website - Clojure backend Clojurescript front end (React)
The app runs on Java 11, inside a Docker container


The website includes:
- Server with handler
- Postgres backend (AWS RDS)
- Single page React app with navigation between screens


*** PREREQUISITES
INSTALL NPM: Must have NPM installed to manage JS dependencies.

On Ubuntu::
```
sudo apt-get update
sudo apt-get install npm
```


*** Development environment

CIDER - Editor configuration
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



*** Deployment

CREATE ELASTIC BEANSTALK ENVIRONMENT
```
aws elasticbeanstalk create-environment \
    --application-name andrewslai \
    --environment-name staging \
    --solution-stack-name "64bit Amazon Linux 2018.03 v2.12.14 running Docker 18.06.1-ce" \
    --region us-east-1
    --option-settings file://eb-default-vpc.json
```

MODIFY RESUME CARDS:
`cider-jack-in-with-profile upload`
in the db.clj namespace, look at comment at bottom of file
use this to update the database - but be careful! It overwrites
existing DB and repopulates from scratch.

TERRAFORM
`terraform plan -var-file=andrewslai_secrets.tfvars`


*** HELPFUL COMMANDS

DOCKER
```
docker build -t andrewslai .
sudo docker run --network="host" -p 5000:5000 andrewslai
sudo docker ps
sudo docker stop 02d64e84e7c3
```

ELASTIC BEANSTALK
```
eb platform select
eb platform show
eb ssh staging
```

POSTGRES

*Modify default postgres user password*
- edit pg_hba.conf and modify to "trust"
- stop service `service postgres stop`
- `service postgres start`
- login to psql: `sudo su - postgres`
```
psql
ALTER USER posautocompletetgres WITH PASSWORD xxxxxxxx;
```


PSQL:

```
   \du :: list users
   \dt :: List tables
   select current_user;
   select current_database();
   select schema_name from information_schema.schemata;
```


###### TO DO
## Modify default data in database so that HTML is directly saved in DB
## Modify the cljs code to recognize new data format
## Get JS working on load (component did mount)
## Figure out why localhost has different landing page from website
## Modify AWS DB and deploy new code to website;;

## Start writing articles about building an OS
