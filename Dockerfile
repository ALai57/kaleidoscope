FROM openjdk:21-jdk-slim

# This list excludes specific classes from instrumentation
# To avoid a lot of Java instrumentation at start time,
# we only instrument `kaleidoscope` namespaces, and exclude
# all other Clojure namespaces from dependencies (reitit, etc)
ARG exclude_list
ENV TRACE_EXCLUDE_LIST=$exclude_list

RUN mkdir -p /kaleidoscope
WORKDIR /kaleidoscope

COPY target/kaleidoscope.jar .

# Only use the /kaleidoscope/assets folder if serving content from the local filesystem
#RUN mkdir -p /kaleidoscope/assets
#COPY resources/public/css assets/public/css
#COPY resources/public/images assets/public/images
#COPY resources/public/index.html assets/public/index.html
#COPY resources/public/js assets/public/js

# OpenTelemetry requires a javaagent. Javaagents are a class
# that intercepts applications on a JVM and modify their bytecode
COPY opentelemetry-javaagent.jar .


# Configuring max and min heap sizes to be identical is a
# best practice for optimizing GC performance.
RUN echo "***********************"
RUN echo $TRACE_EXCLUDE_LIST
RUN echo "***********************"

# CMD with a vector or exec starts a process and the process gets signals
# instead of a /bin/sh process getting PID 1 and getting all signals.
# https://vsupalov.com/docker-compose-stop-slow/
CMD exec java -Xms512m -Xmx512m \
    # Opentelemetry support. This configures where the OTel agent
    # sends the traces it collects.
    -javaagent:opentelemetry-javaagent.jar \
    -Dotel.resource.attributes=service.name=kaleidoscope \
    -Dotel.exporter.otlp.protocol=http/protobuf \
    -Dotel.exporter.otlp.endpoint=${SUMOLOGIC_OTLP_URL} \
    -Dotel.javaagent.debug=true \
    -Dotel.metrics.exporter=none \
    -Dotel.traces.exporter=otlp \
    # Disable auto instrumentation, so we can only enable the things we wwant
    #-Dotel.instrumentation.jetty.enabled=true \
    #-Dotel.instrumentation.aws-sdk.enabled=true \
    #-Dotel.instrumentation.jdbc-datasource.enabled=true \
    ### -Dotel.instrumentation.common.default-enabled=true \
    ### -Dotel.javaagent.exclude-classes=$TRACE_EXCLUDE_LIST \
    -jar kaleidoscope.jar


EXPOSE 5000
