# Local development

For local development against locally running services:  
1) [Set up Postgres](#postgres)  
2) [Set up Keycloak](#keycloak)  
3) [Start the app](#locally-running-app-connected-to-locally-running-services).


For local development against cloud services:  
1) [Start the app](#locally-running-app-connected-to-cloud-services).

## Postgres
If you want to set up a locally running Postgres to connect to.  

#### Install
Install postgres (on Ubuntu, `sudo apt get install postgresql`)

On MacOS, if you get an error, you may need to create or modify your `/etc/sysctl.conf` file and allow more "shared memory". [An explanation can be found in this article.]( https://benscheirman.com/2011/04/increasing-shared-memory-for-postgres-on-os-x)

#### Database setup
Create a database 
```bash 
createdb <DATABASE-NAME>
```  

Create a superuser with permissions on the database
```sql
CREATE USER <USER-GOES-HERE> WITH PASSWORD <PASSWORD-GOES-HERE>;
GRANT ALL PRIVILEGES ON SCHEMA PUBLIC TO <USER-GOES-HERE>;
```

#### Postgres notes
On Linux, if you just installed postgresql, you may need to change some
permissions to be able to log on as a superuser to create a db or new user  

First edit pg_hba.conf and modify to "trust". 

Then you should be able to restart postgres and login.
```bash
service postgres stop
service postgres start
sudo su - postgres
```

#### Connect to DB
Set environment variables to allow you to connect to the database.
An example configuration is below
```
ANDREWSLAI_DB_HOST=localhost
ANDREWSLAI_DB_USER=andrewslai
ANDREWSLAI_DB_PASSWORD=andrewslai
ANDREWSLAI_DB_NAME=andrewslai
ANDREWSLAI_DB_PORT=5432
```

## Locally running app connected to locally running services

**_Setup steps_**  
Create an uberjar and build the docker container. Start keycloak
```bash
lein do clean, uberjar
docker build -t andrewslai .
docker run --network host \
            -e DB_USER=keycloak  \
            -e DB_PASSWORD=keycloak \
            -e DB_DATABASE=keycloak \
            -e DB_VENDOR=POSTGRES \
            -e DB_ADDR=""  \
            jboss/keycloak -Djgroups.bind_addr=127.0.0.1
```

**_Startup_**  
Edit the `.env.local` file to provide env vars.
```bash
docker run -d --rm --network host --env-file=.env.local -p 5000:5000 andrewslai
```
There is a template for what the `.env.local` in `env.local.example`

```bash
ANDREWSLAI_AUTH_TYPE=none ANDREWSLAI_DB_TYPE=embedded-h2 ANDREWSLAI_STATIC_CONTENT_TYPE=local ANDREWSLAI_STATIC_CONTENT_FOLDER='../andrewslai-frontend/resources/public' lein run

```

## Locally running app connected to cloud services

**_Setup steps_**  
Create an uberjar and build the docker container.
```bash
lein do clean, uberjar
docker build -t andrewslai .
```

**_Startup_**  
Edit the `.env.aws` file to provide the correct environment variables.
```bash
docker run -d --rm --env-file=.env.aws -p 5000:5000 andrewslai
