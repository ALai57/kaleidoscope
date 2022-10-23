FROM openjdk:11-jre-slim

RUN mkdir -p /andrewslai
WORKDIR /andrewslai

COPY target/andrewslai.jar .

# Only use the /andrewslai/assets folder if serving content from the local filesystem
#RUN mkdir -p /andrewslai/assets
#COPY resources/public/css assets/public/css
#COPY resources/public/images assets/public/images
#COPY resources/public/index.html assets/public/index.html
#COPY resources/public/js assets/public/js

CMD java -jar andrewslai.jar -Xmx256m

EXPOSE 5000
