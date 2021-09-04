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

#### Database setup
Create a database 
```bash 
createdb <DATABASE-NAME>
```  

Create a superuser with permissions on the database
```sql
CREATE USER <USER-GOES-HERE> WITH PASSWORD <PASSWORD-GOES-HERE>;
GRANT ALL PRIVILEGES ON SCHEMA PUBLIC TO USER <USER-GOES-HERE>;
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

## Keycloak
If you want to set up a locally running Keycloak (IDP) instance to connect to.  

#### Installation
The keycloak IDP can be run from a docker image [jboss/keycloak](https://hub.docker.com/r/jboss/keycloak/)

#### Running a local Keycloak instance
Keycloak requires a persistence layer to keep track of users, permisisons, etc.

**_In-memory H2 DB as the persistence layer_**  
```bash
docker run -e KEYCLOAK_USER=<USERNAME> \
           -e KEYCLOAK_PASSWORD=<PASSWORD> 
           jboss/keycloak
```

**_Postgres as persistence layer_**  
```bash
docker run --network host \
            -e DB_USER=keycloak  \
            -e DB_PASSWORD=keycloak \
            -e DB_DATABASE=keycloak \
            -e DB_VENDOR=POSTGRES \
            -e DB_ADDR=""  \
            jboss/keycloak -Djgroups.bind_addr=127.0.0.1
```

The Djgroups.bind_addr argument seems to refer to the address that the server
will bind to on the local network. When this is set to localhost, the Wildfly
(JBoss) application server will start on port 9990.

#### Confirming Keycloak is running
If you navigate to 172.17.0.1:8080/auth you can see the Keycloak admin console.
The environment variable, KEYCLOAK_LOGLEVEL=DEBUG can be used to configure log
level

If you navigate to localhost:9990 you can see the process (JBoss or Wildfly)
that manages deployment


#### Keycloak notes
Keycloak will complain if it doesn't find any valid users. You can supply a
admin user via command line arguments. With a Keycloak container running:
```bash
docker exec <CONTAINER_NAME> /opt/jboss/keycloak/bin/add-user.sh \
        -u keycloak \
        -p keycloak
```

There is also an `add-user-keycloak.sh` script that seems to add users
specifically to Keycloak, and not to the Application Server


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
```
