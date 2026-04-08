# Stage 1: Build a custom JRE containing only the modules this app needs.
# jlink requires the full JDK, so we use the JDK image here only.
FROM eclipse-temurin:21-jdk-jammy AS jre-builder

# Module list derived from:
#   jdeps --multi-release 21 --print-module-deps target/kaleidoscope.jar
# plus three modules jdeps cannot detect (loaded at runtime, not via static bytecode refs):
#   java.instrument    - required for the OTEL javaagent premain mechanism
#   jdk.crypto.ec      - elliptic-curve TLS cipher suites (needed for SSL DB connections)
#   jdk.unsupported    - sun.misc.Unsafe (required by byte-buddy, which the OTEL agent uses)
#
#   java.base          - core Java
#   java.desktop       - java.awt / javax.imageio / javax.swing / java.beans (image-resizer + Clojure inspector)
#   java.instrument    - OTEL javaagent [runtime-only, not in jdeps output]
#   java.logging       - java.util.logging (AWS SDK, Keycloak, many deps)
#   java.management    - javax.management / JMX (HikariCP pool monitoring)
#   java.naming        - javax.naming / JNDI (JDBC drivers, AWS SDK)
#   java.net.http      - HTTP client (AWS SDK v2 SSOOIDC, Stripe)
#   java.scripting     - javax.script / ScriptEngine (referenced by bundled deps)
#   java.security.jgss - org.ietf.jgss / GSSAPI / Kerberos (TLS negotiation, AWS auth)
#   java.sql           - JDBC API / javax.sql (PostgreSQL, HikariCP)
#   java.xml           - javax.xml / org.w3c.dom / org.xml.sax (AWS SDK v1 response parsing)
#   jdk.crypto.ec      - elliptic-curve TLS [runtime-only, not in jdeps output]
#   jdk.unsupported    - sun.misc.Unsafe [runtime-only, not in jdeps output]
RUN jlink \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --strip-debug \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.scripting,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported \
    --output /custom-jre

# Stage 2: Minimal Debian runtime — no JDK tooling, no package manager cruft.
FROM debian:12-slim

# java.desktop (used headlessly for ImageIO) requires fontconfig and freetype at the OS level.
RUN apt-get update && apt-get install -y --no-install-recommends \
    libfreetype6 \
    libfontconfig1 \
    && rm -rf /var/lib/apt/lists/*

COPY --from=jre-builder /custom-jre /opt/jre
ENV PATH="/opt/jre/bin:$PATH"
ENV JAVA_HOME="/opt/jre"

# This list excludes specific classes from instrumentation
# To avoid a lot of Java instrumentation at start time,
# we only instrument `kaleidoscope` namespaces, and exclude
# all other Clojure namespaces from dependencies (reitit, etc)
ARG exclude_list
ENV TRACE_EXCLUDE_LIST=$exclude_list

RUN mkdir -p /kaleidoscope
WORKDIR /kaleidoscope

COPY target/kaleidoscope.jar .

# Slim OTEL agent built from opentelemetry-java-instrumentation v2.2.0 with only the
# instrumentation modules used by this app (jdbc, hikaricp, jetty, aws-sdk-1.11,
# apache-httpclient, okhttp). ~15MB vs ~200MB for the full agent.
COPY opentelemetry-javaagent.jar .

# CMD with a vector or exec starts a process and the process gets signals
# instead of a /bin/sh process getting PID 1 and getting all signals.
# https://vsupalov.com/docker-compose-stop-slow/
CMD exec java -Xms512m -Xmx512m \
    # Run ImageIO headlessly — no display required on the server.
    -Djava.awt.headless=true \
    # Opentelemetry support. This configures where the OTel agent
    # sends the traces it collects.
    -javaagent:opentelemetry-javaagent.jar \
    -Dotel.resource.attributes=service.name=kaleidoscope \
    -Dotel.exporter.otlp.protocol=http/protobuf \
    -Dotel.exporter.otlp.endpoint=${SUMOLOGIC_OTLP_URL} \
    -Dotel.metrics.exporter=none \
    -Dotel.traces.exporter=otlp \
    # Slim agent only contains: jdbc, hikaricp, jetty, aws-sdk-1.11, apache-httpclient, okhttp.
    # All included modules are enabled by default — no need to opt in individually.
    -Dotel.javaagent.exclude-classes=$TRACE_EXCLUDE_LIST \
    -jar kaleidoscope.jar

EXPOSE 5000
