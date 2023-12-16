# Keycloak Local development

It's useful to be able to connect to a locally-running instance of Keycloak for
integration testing the application. This guide explains how to set up the Keycloak 
Identity Provider from it's officially supported docker image
[quay.io/keycloak](https://quay.io/repository/keycloak/keycloak).

## Notes on the Keycloak upgrade from Wildfly to Quarkus:
Old versions of Keycloak used an Application Server to manage deployment.
However, Keycloak released a new version of the container that does not depend
on an  Application Server (Wildfly) for deployment. This keeps the image and the
container much smaller and simpler. I recently upgraded Keycloak to use the new
image, which depends on Quarkus instead of Wildfly.

It's important to keep in mind that our normal Keycloak deployment in AWS will make use
of a reverse proxy. In AWS, we want to use a reverse proxy (the Load Balancer)
to terminate SSL connections and forward the traffic to Keycloak via HTTP
instead of over HTTPS. 

## Setting up Keycloak with a transient, in-memory database
TODO: fillmein

## Setting up Keycloak with a persistent database
If you have already set up a persistent database, you can fill in the connection details 
and directly run the container. Then, you can connect to it in your browser using the command below:

```
docker run -p 8080:8080 \
    # If you're on a Mac 
    --platform linux/arm64 \
    -e KC_DB=postgres \
    -e KC_DB_URL_HOST=<HOSTNAME> \
    -e KC_DB_DATABASE=<DATABASE-NAME> \
    -e KC_DB_USERNAME=<USERNAME> \
    -e KC_DB_PASSWORD=<PASSWORD> \
    -e KC_HOSTNAME_STRICT=false \
    -e KC_EDGE=proxy \
    -e KC_HTTP_ENABLED=true \
    -e KC_FEATURES=token-exchange \
    quay.io/keycloak/keycloak:20.0.3 start
    ```

### Setting up a Local postgres database for testing
```bash
# Set up a local DB for the first time
createdb keycloak

# Log in and set up a user with correct permissions
psql keycloak
CREATE USER keycloak WITH PASSWORD 'keycloak';
GRANT ALL PRIVILEGES ON SCHEMA public to keycloak;

# Check that the user can login
psql keycloak -U keycloak
```

Make sure that the postgres database is listening on localhost:5432
```sh
lsof -i -P -n | GREP LISTEN
```

Then, you can run the container.
NOTE: The "host.docker.internal" host is a Docker DNS entry that allows the
      Keycloak container to connect to the Postgres instance on the host.
NOTE: The `KEYCLOAK_ADMIN` related env vars create a new Keycloak admin user
      that you can use to administer the Realms.
NOTE: Make sure the database is serving IP traffic by changing the database configuration

### If you're on a Mac
```
docker run -p 8080:8080 \
    --platform linux/arm64 \
    -e KC_DB=postgres \
    -e KC_DB_URL_HOST="host.docker.internal" \
    -e KC_DB_DATABASE="keycloak" \
    -e KC_DB_USERNAME="keycloak" \
    -e KC_DB_PASSWORD="keycloak" \
    -e KC_HOSTNAME_STRICT=false \
    -e KC_EDGE=proxy \
    -e KC_HTTP_ENABLED=true \
    -e KC_FEATURES=token-exchange \
    -e KEYCLOAK_ADMIN=keycloak \
    -e KEYCLOAK_ADMIN_PASSWORD=keycloak \
    quay.io/keycloak/keycloak:20.0.3 start
```

### On Linux
For the Linux solution use https://stackoverflow.com/a/55270528
```
docker run -p 8080:8080 \
    --network=host \
    --platform linux/amd64 \
    -e KC_DB=postgres \
    -e KC_DB_URL_HOST="127.0.0.1" \
    -e KC_DB_DATABASE="keycloak" \
    -e KC_DB_USERNAME="keycloak" \
    -e KC_DB_PASSWORD="keycloak" \
    -e KC_HOSTNAME_STRICT=false \
    -e KC_EDGE=proxy \
    -e KC_HTTP_ENABLED=true \
    -e KC_FEATURES=token-exchange \
    -e KEYCLOAK_ADMIN=keycloak \
    -e KEYCLOAK_ADMIN_PASSWORD=keycloak \
    quay.io/keycloak/keycloak:20.0.3 start --log-level=debug
```

TODO: Set up test realm!!
Then import `test-keycloak-realm.json` to set up the realm and test client
This is a specific example showing how to set up the container connected to a locally running
Postgres instance.
