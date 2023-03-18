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

# Configuring max and min heap sizes to be identical is a
# best practice for optimizing GC performance.
CMD java -jar kaleidoscope.jar -Xms256m -Xmx256m


EXPOSE 5000
