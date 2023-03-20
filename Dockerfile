FROM openjdk:17-jdk-slim

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
CMD java -Xms512m -Xmx512m \
    # Opentelemetry support. This configures where the OTel agent
    # sends the traces it collects.
    -javaagent:opentelemetry-javaagent.jar \
    -Dotel.resource.attributes=service.name=kaleidoscope \
    -Dotel.exporter.otlp.protocol=http/protobuf \
    -Dotel.exporter.otlp.endpoint=${SUMOLOGIC_OTLP_URL} \
    -Dotel.javaagent.debug=true \
    -jar kaleidoscope.jar


EXPOSE 5000
