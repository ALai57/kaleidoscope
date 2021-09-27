# andrewslai

Welcome to my personal website! It is currently hosted at https://andrewslai.com.  

This repository is the website backend. It contains:

- **Backend**: Clojure web server on Java 11.  
- **Infrastructure**: Terraform for AWS cloud infrastructure  

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
``` clojure
lein run
```

#### Run with Docker
After docker build and setting up `.env.local` with correct environment

``` clojure
docker run --env-file=.env.local -p 5000:5000 andrewslai
```

## Development
For local development, see [local-development.md](./docs/local-development.md)

## Deployment
To deploy, follow instructions in [deployment.md](./docs/deployment.md)
