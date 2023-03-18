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

CMD java -jar kaleidoscope.jar -Xmx256m

EXPOSE 5000
