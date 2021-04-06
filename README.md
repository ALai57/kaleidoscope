# andrewslai

Welcome to my personal website! 

At the moment, this repository contains a frontend, backend, and
infrastructure-as-code to set up an Identity provider for managing users and
authentication.

Eventually, I hope to turn this project into a blogging platform where users can
easily create and publish blog content.

## What's included:

       Backend: Dockerized Clojure web server running ring & compojure on Java 11.
     Front end: Re-frame SPA written in Clojurescript
Infrastructure: Terraform for provisioning the cloud infrastructure to run the website

## Cloud infrastructure
Terraform for the following AWS resources:
- ECS Fargate to run the backend
- RDS Postgres for persistence
- S3 for storing the tf backend
- ECS Fargate to run Keycloak IDP
- Single ALB to route traffic to both IDP and to the application

# Installation
## Clojure
The server is a standard Clojure Ring web server.
To get up and running, clone the repo and make sure you have `leiningen` installed.
Run tests with `lein test`

## Clojurescript
The frontend is built using `figwheel-main`
To build the project without any Google Closure optimizations, use `lein
fig:build` (this will also connect a figwheel REPL for interactive development)

To test the Clojurescript app, start a figwheel server with `lein fig:build`,
then navigate to `/tests.html` to see test output

## Postgres
Install postgres (on Ubuntu, `sudo apt get install postgresql`)
Create a database `createdb <DATABASE-NAME>` 
Create a superuser with permissions on the database
`create user <USER-GOES-HERE> with password <PASSWORD-GOES-HERE>`
`grant all privileges on schema public to user <USER-GOES-HERE>`

Set environment variables to allow you to connect to the database.
An example configuration is below
```
ANDREWSLAI_DB_HOST=localhost
ANDREWSLAI_DB_USER=andrewslai
ANDREWSLAI_DB_PASSWORD=andrewslai
ANDREWSLAI_DB_NAME=andrewslai
ANDREWSLAI_DB_PORT=5432
```

On Linux, if you just installed postgresql, you may need to change some
permissions to be able to log on as a superuser to create a db or new user

- edit pg_hba.conf and modify to "trust". 
- stop service `service postgres stop`. 
- `service postgres start`. 
- login to psql: `sudo su - postgres`. 


# Building the uberjar
First, clean the project and build an uberjar `lein do clean, uberjar`

## Building and testing with Docker
After creating an uberjar build the docker container `docker build -t andrewslai .`

If you'd like to run the server in a Docker container connected to an instance
of Postgres running on localhost, you'll need to specify that you want it to run
on the localhost network, and provide the correct environment variables for the
database.
`docker run -d --rm --network host --env-file=.env.local -p 5000:5000 andrewslai`
There is a template for what the `.env.local` should look like in `env.local.example`

If you'd like to run the server in a Docker container connected to an instance
of Postgres running on an AWS database, you'll need to provide the correct
environment variables for the database.
`docker run -d --rm --env-file=.env.aws -p 5000:5000 andrewslai`



# Keycloak Identity Provider
The app is meant to work with the open-source Identity Provider, Keycloak. 

To run Keycloak with an in-memory H2 DB: 
`docker run -e KEYCLOAK_USER=<USERNAME> -e KEYCLOAK_PASSWORD=<PASSWORD> jboss/keycloak`

TO run Keycloak locally and connect to a locally running PSQL database.
`docker run --network host \
            -e DB_USER=keycloak  \
            -e DB_PASSWORD=keycloak \
            -e DB_DATABASE=keycloak \
            -e DB_VENDOR=POSTGRES \
            -e DB_ADDR=""  \
            jboss/keycloak -Djgroups.bind_addr=127.0.0.1`

If you navigate to 172.17.0.1:8080/auth you can see the actual keycloak process
running The environment variable, KEYCLOAK_LOGLEVEL=DEBUG can be used to
configure log level

The Djgroups.bind_addr argument seems to refer to the address that the server
will bind to on the local network. When this is set to localhost, the Wildfly
(JBoss) application server will start on port 9990

If you navigate to localhost:9990 you can see the process (JBoss or Wildfly)
that manages deployment

Keycloak will complain if it doesn't find any valid users. You can supply a
admin user via command line arguments. With a Keycloak container running:
`docker exec <CONTAINER_NAME> /opt/jboss/keycloak/bin/add-user.sh \
        -u keycloak \
        -p keycloak`

There is also an `add-user-keycloak.sh` script that seems to add users
specifically to Keycloak, and not to the Application Server


# How to connect to Keycloak using the keycloak.js client


``` javascript

// Initialize Keycloak object
var kc = Keycloak({"url": "http://172.17.0.1:8080/auth",
                   "realm": "test", 
                   "clientId": "test-login"})

// Initialize connection to Keycloak.
kc.init({"onLoad": "login-required", // Optional - forces user to login at init time
         "checkLoginIframe": false,
         "pkceMethod": "S256"})
         .then(function (authenticated) {console.log(authenticated);})
         .catch(function (authenticated) {console.log("BAADD");})

// If we did not choose `onLoad`: `login-required`
kc.login({'prompt': 'Please authenticate', 'scope': 'roles'})

// Now that we've logged in, we can get the user's profile - I believe this also loads kc.token and kc.idToken, which are the JWTs used to access Keycloak
kc.loadUserProfile().then(function (profile) {console.log(profile)})

=> {username: "andrewtest@test.com", firstName: "Andrew", lastName: "Test", email: "andrewtest@test.com", emailVerified: false, …}attributes: {}email: "andrewtest@test.com"emailVerified: falsefirstName: "Andrew"lastName: "Test"username: "andrewtest@test.com"__proto__: Object

// We can also load user info
kc.loadUserInfo().then(function (profile) {console.log(profile)})

```

