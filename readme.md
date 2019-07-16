
This is my personal website - Clojure backend Clojurescript front end (React)
The app runs on Java 11, inside a Docker container


The website includes:
- Server with handler
- Postgres backend (local and AWS RDS)
- Single page React app with navigation between screens


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

ADD TO BASH PROFILE: FOR SWITCHING DATABASES
`source ./PATH/TO/andrewslai/scripts/db/use_db.sh`
usage: `use_db aws` `use_db local`


LOCAL DATABASE SETUP (POSTGRES)
Run setup.sh in scripts/db


INSTALL NPM: Must have NPM installed to manage JS dependencies.

On Ubuntu::
```
sudo apt-get update
sudo apt-get install npm
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

PACKAGE ARTIFACT AND CHECK IF IT RUNS
```
lein do clean, uberjar
docker build -t andrewslai .
use_db aws
sudo docker run --env-file=.env -p 5000:5000 andrewslai
```

UPLOAD ARTIFACT TO S3
```
zip --exclude '*.git*' --exclude '*node_modules/*' --exclude '*.elasticbeanstalk*' --exclude '*deployment*.zip' -r deployment.zip .
aws s3 mb s3://andrewslai-eb --region us-east-1
aws s3 cp deployment.zip s3://andrewslai-eb --region us-east-1
```

CREATE NEW APPLICATION VERSION
```
aws elasticbeanstalk create-application-version \
    --application-name andrewslai_website \
    --version-label v1 \
    --source-bundle S3Bucket="andrewslai-eb",S3Key="deployment.zip" \
    --auto-create-application \
    --region us-east-1
```

UPDATE ENVIRONMENT WITH NEW APPLICATION VERSION
```
aws elasticbeanstalk update-environment \
    --application-name andrewslai_website \
    --environment-name staging \
    --version-label v1 \
    --region us-east-1
```
NEED TO MANUALLY INPUT ENV VARIABLES RIGHT NOW

REMOTE DATABASE CONNECTION
```
psql \
   --host=$ANDREWSLAI_DB_HOST \
   --port=$ANDREWSLAI_DB_PORT \
   --username=$ANDREWSLAI_DB_USER \
   --dbname=$ANDREWSLAI_DB_NAME \
   --password
```

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
