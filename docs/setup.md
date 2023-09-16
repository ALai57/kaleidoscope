# Setup

## Local development

For local development, you'll need to set up
1) Postgres
2) Keycloak

## Postgres
Install postgres (on Ubuntu, `sudo apt get install postgresql`)
Create a database `createdb <DATABASE-NAME>` 
Create a superuser with permissions on the database
`create user <USER-GOES-HERE> with password <PASSWORD-GOES-HERE>`
`grant all privileges on schema public to user <USER-GOES-HERE>`

Set environment variables to allow you to connect to the database.
An example configuration is below
```
KALEIDOSCOPE_DB_HOST=localhost
KALEIDOSCOPE_DB_USER=andrewslai
KALEIDOSCOPE_DB_PASSWORD=andrewslai
KALEIDOSCOPE_DB_NAME=andrewslai
KALEIDOSCOPE_DB_PORT=5432
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
After creating an uberjar build the docker container `docker build -t kaleidoscope .`

If you'd like to run the server in a Docker container connected to an instance
of Postgres running on localhost, you'll need to specify that you want it to run
on the localhost network, and provide the correct environment variables for the
database.
`docker run -d --rm --network host --env-file=.env.local -p 5000:5000 kaleidoscope`
There is a template for what the `.env.local` should look like in `env.local.example`

If you'd like to run the server in a Docker container connected to an instance
of Postgres running on an AWS database, you'll need to provide the correct
environment variables for the database.
```
./bin/aws-sso-creds  # Only if you need AWS Credentials

docker run -d --rm \
    --env-file=.env.aws \
    -p 5000:5000 \
    kaleidoscope
```



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

