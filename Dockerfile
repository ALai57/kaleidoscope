FROM openjdk:11-jre-slim
RUN mkdir -p /app /app/resources
WORKDIR /app
COPY target/andrewslai.jar .
CMD java -jar andrewslai.jar
EXPOSE 5000
