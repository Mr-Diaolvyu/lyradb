# LyraDB 企业版服务端镜像：Vue 前端 → core/backend 验证 → 最小权限运行时。

FROM node:24-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci
COPY frontend/ ./
RUN npm run lint && npm run typecheck && npm run test && npm run build

FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build
COPY pom.xml ./
COPY core/pom.xml core/pom.xml
COPY backend/pom.xml backend/pom.xml
COPY desktop/pom.xml desktop/pom.xml
COPY core/src core/src
COPY backend/src backend/src
COPY --from=frontend-build /build/frontend/dist backend/src/main/resources/static
RUN mvn -B -q -pl backend -am clean verify

FROM eclipse-temurin:17-jre

# 运行用户固定为不可登录的 10001:10001；COPY、目录所有权和 USER 保持一致。
RUN apt-get update \
    && apt-get install --no-install-recommends -y curl \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 lyradb \
    && useradd --uid 10001 --gid 10001 \
        --home-dir /home/lyradb --create-home --shell /usr/sbin/nologin lyradb \
    && mkdir -p /app/data /home/lyradb/.lyradb/drivers \
    && chown -R 10001:10001 /app /home/lyradb

WORKDIR /app
COPY --from=backend-build --chown=10001:10001 \
    /build/backend/target/lyradb-backend-*.jar /app/app.jar

ENV HOME=/home/lyradb \
    SPRING_PROFILES_ACTIVE=prod \
    LYRADB_EDITION=enterprise

EXPOSE 8080
VOLUME ["/app/data", "/home/lyradb/.lyradb"]

USER 10001:10001

HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
    CMD ["curl", "--fail", "--silent", "--show-error", "http://127.0.0.1:8080/api/app/info"]

ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
