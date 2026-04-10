# Stage 1: Build a custom JRE and generate a CDS archive.
# jlink requires the full JDK, so we use the JDK image here only.
FROM eclipse-temurin:21-jdk-jammy AS jre-builder

# Module list derived from:
#   jdeps --multi-release 21 --print-module-deps target/kaleidoscope.jar
# plus modules jdeps cannot detect (loaded at runtime, not via static bytecode refs):
#   jdk.crypto.ec      - elliptic-curve TLS cipher suites (needed for SSL DB connections)
#   jdk.unsupported    - sun.misc.Unsafe (used by various reflection-heavy libs)
#
#   java.base          - core Java
#   java.desktop       - java.beans (Introspector used by malli/reitit/Clojure reflection)
#   java.instrument    - retained for any runtime instrumentation hooks
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
#
# Output to /opt/jre — the same absolute path used in the runtime stage — so that the
# CDS archive created below encodes consistent module paths.
RUN jlink \
    --no-header-files \
    --no-man-pages \
    --compress=2 \
    --strip-debug \
    --add-modules java.base,java.desktop,java.instrument,java.logging,java.management,java.naming,java.net.http,java.scripting,java.security.jgss,java.sql,java.xml,jdk.crypto.ec,jdk.unsupported \
    --output /opt/jre

# Copy the JAR to the same absolute path it will occupy at runtime so that the
# CDS archive records the correct classpath. Any mismatch between creation-time
# and runtime paths causes the archive to be silently skipped.
RUN mkdir -p /kaleidoscope
COPY target/kaleidoscope.jar /kaleidoscope/kaleidoscope.jar

# AppCDS — step 1: training run.
# Starts the app, which loads Clojure + all required namespaces before reaching
# env-var validation. The JVM writes the class list on exit (even abnormal exit),
# so we get a near-complete picture despite the expected startup failure.
# The OTEL agent is intentionally excluded: it transforms bytecodes dynamically
# and those transformations must not be frozen into the static archive.
RUN /opt/jre/bin/java \
    -Xshare:off \
    -XX:DumpLoadedClassList=/kaleidoscope/classes.lst \
    -Djava.awt.headless=true \
    -jar /kaleidoscope/kaleidoscope.jar \
    2>/dev/null || true

# AppCDS — step 2: build the static archive from the class list.
# -Xshare:dump loads every class listed, writes them into the shared archive,
# then exits without invoking main(). Application classes come from -cp;
# JDK module classes are located automatically from the custom JRE.
RUN /opt/jre/bin/java \
    -Xshare:dump \
    -XX:SharedArchiveFile=/kaleidoscope/app-cds.jsa \
    -XX:SharedClassListFile=/kaleidoscope/classes.lst \
    -Djava.awt.headless=true \
    -cp /kaleidoscope/kaleidoscope.jar \
    2>/dev/null || true

# Stage 2: Minimal distroless runtime — no shell, no package manager, no root tools.
# ~20MB compressed vs ~74MB for debian:12-slim.
FROM gcr.io/distroless/base-debian12

COPY --from=jre-builder /opt/jre /opt/jre
ENV PATH="/opt/jre/bin:$PATH"
ENV JAVA_HOME="/opt/jre"

# OTEL configuration via standard environment variables.
# The programmatic SDK (AutoConfiguredOpenTelemetrySdk) reads OTEL_* env vars at startup.
# OTEL_EXPORTER_OTLP_ENDPOINT must be set as a Fly.io secret:
#   fly secrets set OTEL_EXPORTER_OTLP_ENDPOINT=<value>
ENV OTEL_SERVICE_NAME="kaleidoscope"
ENV OTEL_EXPORTER_OTLP_PROTOCOL="http/protobuf"
ENV OTEL_METRICS_EXPORTER="none"
ENV OTEL_TRACES_EXPORTER="otlp"

WORKDIR /kaleidoscope

COPY target/kaleidoscope.jar .
COPY --from=jre-builder /kaleidoscope/app-cds.jsa .

# Exec form — distroless has no shell, so exec form (JSON array) is required.
# java receives PID 1 directly and handles signals cleanly.
# All OTEL config is read from the OTEL_* env vars set above; the SDK is
# initialized programmatically at startup (no javaagent required).
# -Xshare:auto uses the CDS archive when present and compatible, falls back silently
# if the archive is stale (safer than -Xshare:on for production).
# -XX:SharedArchiveFile loads the AppCDS archive built above, pre-mapping class
# metadata for JDK + all Clojure/library classes.
CMD ["java", "-Xshare:auto", "-XX:SharedArchiveFile=/kaleidoscope/app-cds.jsa", "-Xms512m", "-Xmx512m", "-Djava.awt.headless=true", "-jar", "kaleidoscope.jar"]

EXPOSE 5000
