# andrewslai

Welcome to my personal website! It is currently hosted at https://andrewslai.com.  

This repository is the website backend. It contains:

- **Backend**: Clojure web server that uses Virtual Hosting to serve two different apps: 
               a blog (andrewslai), and a photo viewer app  
- **Infrastructure**: Terraform for AWS cloud infrastructure  

## Architecture of the Clojure namespaces

![Architecture](2022-09-13-architecture.svg)
Figure 1. A high level view of the architecture of the Clojure namespaces in the app.

1. At start time, the `main` namespace uses the `init.config` namespace to parse
   environment variables and creates components to inject into the app
2. `init.config` starts components

### System Components
1. `:virtual-hosting`: Allows you to direct multiple URLs to a single JVM and
   serve multiple websites/apps from a single instance of the app. Used to serve
   separate apps on `andrewslai.com` and `caheriaguilar.and.andrewslai.com`
   (Route53 points both domains to the same load balancer, and when requests hit
   the ECS app, `:virtual-hosting` is used to serve different apps on different
   domains).
2. `:persistence.rdbms`: An RDBMS is used as a persistent data store. Depending
   on environment variables, the app could connect to a live Postgres instance
   that is accessible via the network, or could start an embedded DB (H2 or
   Postgres) for testing.
3. `:persistence.filesystem`: The app serves static files from a Filesystem.
   For the wedding app, a Filesystem used to store pictures and media. Both apps
   serve `index.html` and associated Javascript from a Filesystem (S3). Can be
   set to an S3 implementation, or an in-memory filesystem for testing.
4. `:http-api.middleware`: Multiple stacks of middleware are defined here and
   can be composed together or used to configure how a given Ring handler
   behaves.
   a. (Static-Content middleware): If static content middleware is enabled, the
       app will serve static content from the `:persistence.filesystem`
       implementation - i.e. a request to `/media/foo.svg` would try to look up
       the content located at path `/media/foo.svg` in the `:persistence.filesystem`.
       In a live implementation, this is basically a thin wrapper that serves
       static content in an S3 bucket via the Ring handler.
5. `:http.auth`: Resources in the HTTP API should be protected against unwanted
   access. This component is used to configure how users Authenticate over HTTP.
   Can use different `buddy` backends - some that check HTTP request headers for
   appropriate tokens, and others that accept everyone as authenticated (for
   testing). The normal live implementation is a Keycloak backend, which takes
   Authorization Bearer tokens in the HTTP header, parses them, and reaches out
   to a live Keycloak server/IDP over the network to verify the user's identity.

### Additional configuration
1. `init.config` HTTP Access rules: Can be set at run time to protect http
   routes behind role-based access criteria. Used to determine which user scopes
   are able to access specific resources on the server

## Installation/setup
Clone the repo and install [leiningen](https://leiningen.org/).  

#### Tests
```bash
lein test

```

#### Build: Uberjar
```bash
lein do clean, uberjar
```

#### Build: Docker
```bash
lein do clean, uberjar
docker build -t andrewslai .
```

#### Run without Docker
``` bash
lein run
```

#### Run with Docker
After docker build and setting up `.env.docker.local` with correct environment

``` bash
docker run --env-file=.env.docker.local -p 5000:5000 andrewslai
```

To serve a local copy of the Andrewslai frontend, mount a volume to the Docker
container.
``` bash
docker run --env-file=.env.docker.local \
            -p 5000:5000 \
            -v /Users/alai/spl/andrewslai-frontend/resources/public:/andrewslai-frontend/resources/public \
            andrewslai
```


## Development
For local development, see [local-development.md](./docs/local-development.md)

## Deployment
To deploy, follow instructions in [deployment.md](./docs/deployment.md)
