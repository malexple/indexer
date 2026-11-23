FROM eclipse-temurin:21-jre
RUN useradd -r -u 10001 app && mkdir /data && chown app /data
USER app
WORKDIR /app
COPY build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]