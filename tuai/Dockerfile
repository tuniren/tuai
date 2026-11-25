ARG RUNTIME_IMAGE=eclipse-temurin:25-jre-jammy
FROM ${RUNTIME_IMAGE}

WORKDIR /app
COPY --chown=10001:10001 target/tuai-0.1.0-SNAPSHOT.jar /app/tuai.jar

ENV SERVER_ADDRESS=0.0.0.0 SERVER_PORT=8080
USER 10001:10001
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=70.0", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/tuai.jar"]
