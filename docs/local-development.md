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
KALEIDOSCOPE_DB_HOST=localhost
KALEIDOSCOPE_DB_USER=andrewslai
KALEIDOSCOPE_DB_PASSWORD=andrewslai
KALEIDOSCOPE_DB_NAME=andrewslai
KALEIDOSCOPE_DB_PORT=5432
```

## Run app in Docker container

**_Setup steps_**  
Create an uberjar and build the docker container. Start keycloak
```bash
./bin/uberjar && ./bin/docker-build
```

**_Startup in Docker Container_**  
In the command below, replace `.env.aws` with the environment file you'd like to use.

If you want to be connected to aws use the following with the `AWS_PROFILE` environment variable set to the profile you want:
```bash
export AWS_PROFILE=andrew-home-aws-profile
aws sso login
```

Then run the container
```bash
docker run -d --rm \
  --env-file=.env.aws \
  -p 5000:5000 \
  kaleidoscope
```

## Run app via `clojure`
**_Startup Without Container_**  
```bash
./bin/run --environment=.env.local
```

## Serve kaleidoscope from HTTPS
Some use cases (such as Cognito) must redirect to an HTTPS target. So the
locally running server MUST be available on HTTPS. By setting the
`KALEIDOSCOPE_ENABLE_SSL` environment variable, you can start the server using
the SSL cert in `./resources/ssl`, listening on port 5433.

There is currently an SSL cert in the `./resources/ssl` that can be used for local development
This cert is NOT SAFE for any other usages except for local testing

### How to generate an SSL cert
https://web.dev/articles/how-to-use-local-https
https://auth0.com/blog/using-https-in-your-development-environment/


In order to generate a new cert:
```sh
sudo apt install mkcert 
framesetsudo apt install libnss3-tools

brew install mkcert

```

```sh
mkcert -install
```


Then, create a certificate and keypair for development environment
``` bash
mkcert andrewslai.localhost
```
