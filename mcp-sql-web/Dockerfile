FROM eclipse-temurin:21-jre
RUN useradd --system --no-create-home site
USER site
WORKDIR /app
COPY build/libs/app.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
