ARG RUNTIME_IMAGE=eclipse-temurin:25.0.4.1_1-jre-jammy
FROM ${RUNTIME_IMAGE}
ARG BUILD_REVISION=development
LABEL org.opencontainers.image.title="Clone Me" \
      org.opencontainers.image.revision="${BUILD_REVISION}" \
      cyou.jujube.schema-version="2"
ENV CLONE_BUILD_REVISION=${BUILD_REVISION} \
    SPRING_PROFILES_ACTIVE=prod \
    CLONE_DATA_DIR=/data \
    CLONE_LOG_FILE=/data/logs/clone-me.jsonl
RUN groupadd --gid 10001 clone && useradd --uid 10001 --gid 10001 --no-create-home --shell /usr/sbin/nologin clone \
    && mkdir -p /app /data && chown 10001:10001 /data
WORKDIR /app
COPY --chmod=0444 backend/target/clone-me-0.1.0-SNAPSHOT.jar /app/app.jar
USER 10001:10001
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=8s --start-period=45s --retries=3 \
  CMD ["java", "-Xmx64m", "-jar", "/app/app.jar", "--healthcheck"]
ENTRYPOINT ["java", "--enable-native-access=ALL-UNNAMED", "-XX:+ExitOnOutOfMemoryError", "-Xms128m", "-Xmx512m", "-XX:MaxDirectMemorySize=128m", "-jar", "/app/app.jar"]
