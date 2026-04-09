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
#   java.desktop       - java.beans (Introspector used by malli/reitit/Clojure reflection)
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

# Stage 2: Minimal distroless runtime — no shell, no package manager, no root tools.
# ~20MB compressed vs ~74MB for debian:12-slim.
FROM gcr.io/distroless/base-debian12

COPY --from=jre-builder /custom-jre /opt/jre
ENV PATH="/opt/jre/bin:$PATH"
ENV JAVA_HOME="/opt/jre"

# OTEL configuration via standard environment variables.
# The OTEL Java agent reads OTEL_* env vars natively — no shell expansion needed.
# OTEL_EXPORTER_OTLP_ENDPOINT must be set as a Fly.io secret (previously, the shell
# CMD injected the SUMOLOGIC_OTLP_URL secret via ${} expansion; set the same value
# under this name: fly secrets set OTEL_EXPORTER_OTLP_ENDPOINT=<value>).
ENV OTEL_SERVICE_NAME="kaleidoscope"
ENV OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_TRACES_EXPORTER="otlp"

# Slim agent only contains: jdbc, hikaricp, jetty, aws-sdk-1.11, apache-httpclient, okhttp.
# Baked in at build time from the compiled class list; excludes all non-kaleidoscope namespaces.
# Uses the standard OTEL env var so no -D flag is needed in the CMD.
ARG exclude_list
ENV OTEL_JAVAAGENT_EXCLUDE_CLASSES=$exclude_list

WORKDIR /kaleidoscope

COPY target/kaleidoscope.jar .

# Slim OTEL agent built from opentelemetry-java-instrumentation v2.2.0 with only the
# instrumentation modules used by this app. ~15MB vs ~200MB for the full agent.
COPY opentelemetry-javaagent.jar .

# Exec form — distroless has no shell, so exec form (JSON array) is required.
# java receives PID 1 directly and handles signals cleanly.
# All OTEL config is read from the OTEL_* env vars set above.
CMD ["java", "-Xms512m", "-Xmx512m", "-Djava.awt.headless=true", "-javaagent:opentelemetry-javaagent.jar", "-jar", "kaleidoscope.jar"]

EXPOSE 5000
