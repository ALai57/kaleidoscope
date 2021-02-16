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

