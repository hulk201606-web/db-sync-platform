# 两阶段构建：Maven 打包 Spring Boot 可执行 JAR（内含前端 static 产物）。
# 前端产物由 `npm run build:server` 生成并已随仓库提供；若改了前端源码，
# 请先在本地或 CI 执行该命令再构建镜像（见 .github/workflows/ci.yml）。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /src
COPY pom.xml .
COPY db-sync-common ./db-sync-common
COPY db-sync-dialect ./db-sync-dialect
COPY db-sync-metadata ./db-sync-metadata
COPY db-sync-core ./db-sync-core
COPY db-sync-migration ./db-sync-migration
COPY db-sync-cdc ./db-sync-cdc
COPY db-sync-compare ./db-sync-compare
COPY db-sync-cutover ./db-sync-cutover
COPY db-sync-api ./db-sync-api
COPY db-sync-server ./db-sync-server
RUN mvn -B -q -DskipTests package

FROM eclipse-temurin:17-jre AS runtime
WORKDIR /app
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd -r dbsync && useradd -r -g dbsync dbsync
COPY --from=build /src/db-sync-server/target/db-sync-server-*.jar /app/app.jar
# lib/ 用于放 Oracle/DM8/DB2 等受授权限制的驱动（loader.path）
COPY lib /app/lib
RUN mkdir -p /app/data /app/reports && chown -R dbsync:dbsync /app
USER dbsync
EXPOSE 8080
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Dloader.path=/app/lib -jar /app/app.jar"]
HEALTHCHECK --interval=15s --timeout=5s --start-period=60s --retries=5 \
  CMD curl -fsS http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1
