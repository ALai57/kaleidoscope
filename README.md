# andrewslai

Welcome to my personal website! This repository is the website backend. It
contains:

- **Backend**: Clojure web server on Java 11.  
- **Infrastructure**: Terraform for AWS cloud infrastructure  

# Installation/setup
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

# Development
For local development, see [local-development.md](../docs/local-development.md)
