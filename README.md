# Kaleidoscope
![master](https://github.com/ALai57/kaleidoscope/actions/workflows/clojure.yml/badge.svg?branch=master)

[Kaleidoscope](https://kaleidoscope.pub) is a content management system for blogging.  

## How the Kaleidoscope CMS works
The Kaleidoscope backend is built to host multiple different user sites at the
same time. To do this, the Kaleidoscope server inspects incoming HTTP requests
and determines which site is the target by looking at the HTTP request's Host
header (e.g. is this request for `andrewslai.com` or for `caheriaguilar.com`?).
Then, it determines if the user has permissions to access the resources for that
site and serves the resources if the user has the correct permissions.

## Architecture of the Clojure namespaces

![Architecture](2023-04-25-architecture.svg)
Figure 1. A high level view of the architecture of the Clojure namespaces in the app.

The Kaleidoscope app has 3 distinct layers: 
1. Persistence layer: The layer responsible for storing/retrieving all of the
   key data structures. 
2. Api: The layer responsible for encoding the logic of how the key data
   structures should behave. 
3. HTTP Api. The layer that exposes the Api to the outside world.

## App startup 

At start time, the `kaleidoscope.main` namespace uses the
`kaleidoscope.init.env` namespace to inspect the environment and determine how
to boot the components needed to start the app.

The `kaleidoscope.init.env` namespace has `boot-instructions` that change which
components the app starts up based on the environment variables. For example,
the `database-boot-instructions` (shown below) define 3 different options for
the database component - `postgres`, `embedded-h2` and `embedded-postgres`. 

``` clojure
(def database-boot-instructions
  {:name      :database-connection
   :path      "KALEIDOSCOPE_DB_TYPE"
   :launchers {"postgres"          (fn  [env]
                                     (let [ds (connection/->pool HikariDataSource
                                                                 (env->pg-conn env))]
                                       (initialize-connection-pool! ds)
                                       ds))
               "embedded-h2"       (fn [_env] (embedded-h2/fresh-db!))
               "embedded-postgres" (fn [_env] (embedded-pg/fresh-db!))}
   :default   "postgres"})
```

If the environment has `KALEIDOSCOPE_DB_TYPE=postgres` then the app will use the
`postgres` launcher to start the database.

### App startup options

`KALEIDOSCOPE_DB_TYPE`: Determines what database type to use

| Value             | Description                                                                 |
|-------------------|-----------------------------------------------------------------------------|
| postgres          | Connect to an external Postgres instance                                    |
| embedded-h2       | Start an in-JVM, ephemeral H2 instance, seeded with some example data       |
| embedded-postgres | Start an in-JVM, ephemeral Postgres instance, seeded with some example data |



`KALEIDOSCOPE_AUTH_TYPE` Determines how to authenticate users

| Value                  | Description                                                                                                            |
|------------------------|------------------------------------------------------------------------------------------------------------------------|
| keycloak               | Connect to a Keycloak instance for Authentication. Makes a network request for Auth.                                   |
| always-unauthenticated | Always return an unauthenticated user. Does not make a network request.                                                |
| always-authenticated   | Always return an authenticated user with admin permissions on the supported `domains`. Does not make a network request |


`KALEIDOSCOPE_AUTHORIZATION_TYPE` Determines how to authorize users

| Value                   | Description                                                     |
|-------------------------|-----------------------------------------------------------------|
| public-access           | Allow any authenticated user to access all resources            |
| use-access-control-list | Use `KALEIDOSCOPE-ACCESS-CONTROL-LIST` to determine permissions |


`KALEIDOSCOPE_STATIC_CONTENT_TYPE` Determine where to look for static resources

| Value            | Description                                                      |
|------------------|------------------------------------------------------------------|
| none             | Don't set up any static resources                                |
| s3               | Use S3 to serve static resources. Must be able to connect to AWS |
| in-memory        | Use an in-memory filesystem. Useful for testing                  |
| local-filesystem | Serve static content from the local filesystem                   |

Some launch options require additional environment variables to start up (e.g.
databases need connection variables). If you don't supply these variables, the
app will send verbose error messages to help you configure the environment
properly.

## Installation/setup
Clone the repo

#### Build: Uberjar
```bash
./bin/uberjar
```

#### Build: Docker
```bash
./bin/uberjar && ./bin/docker-build
```

#### Run without Docker
``` bash
./bin/run
```

#### Run with Docker
After docker build and setting up `.env.docker.local` with correct environment

``` bash
docker run --env-file=.env.docker.local -p 5000:5000 kaleidoscope
```

To serve a local copy of the Kaleidoscope frontend, mount a volume to the Docker
container.

Set the `$KALEIDOSCOPE_UI_HOME` environment variable as the fully-qualified path
to the kaleidoscope-ui repo and run the following command:

``` bash
docker run --env-file=.env.docker.local \
            -p 5000:5000 \
            -v $KALEIDOSCOPE_UI_HOME/resources/public:/kaleidoscope-ui/resources/public \
            kaleidoscope
```

#### Tests
```bash
./bin/test
```

## Development
For local development, see [local-development.md](./docs/local-development.md)

## Deployment
To deploy, follow instructions in [deployment.md](./docs/deployment.md)
