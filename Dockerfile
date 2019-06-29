FROM openjdk:11-jre-slim
RUN mkdir -p /app /app/resources
WORKDIR /app
COPY target/full-stack-template.jar .
COPY resources/public resources/public
CMD java -jar full-stack-template.jar
EXPOSE 5000