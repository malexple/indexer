FROM eclipse-temurin:21-jre
RUN useradd --system --create-home --uid 10001 app \
    && mkdir /data && chown app /data
WORKDIR /app
COPY mcp-sql-service-0.1.0.jar app.jar
ENV MCPSQL_DATA_DIR=/data \
    JAVA_TOOL_OPTIONS="-Xmx384m"
VOLUME /data
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]