FROM openjdk:11-jre-slim
RUN mkdir -p /app /app/resources
WORKDIR /app
COPY target/andrewslai.jar .
COPY resources/public/css resources/public/css
COPY resources/public/images resources/public/images
COPY resources/public/*.html resources/public/
COPY resources/public/js/*.js resources/public/js/
COPY resources/public/js/compiled/*.js resources/public/js/compiled/
CMD java -jar andrewslai.jar
EXPOSE 5000
