# LyraDB 服务端镜像：多阶段构建（前端 Vue → 嵌入后端 static → Spring Boot fat jar）
# 构建：docker build -t lyradb .
# 运行：推荐使用 docker-compose.yml（见项目根目录）

# ---------- 阶段 1：构建前端 ----------
FROM node:20-alpine AS frontend-build
WORKDIR /build/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --registry=https://registry.npmmirror.com
COPY frontend/ ./
RUN npm run build

# ---------- 阶段 2：构建后端（嵌入前端产物） ----------
FROM maven:3.9-eclipse-temurin-17 AS backend-build
WORKDIR /build/backend
COPY backend/pom.xml ./
COPY backend/src ./src
COPY --from=frontend-build /build/frontend/dist ./src/main/resources/static
RUN mvn -q clean package -DskipTests

# ---------- 阶段 3：运行时 ----------
FROM eclipse-temurin:17-jre
WORKDIR /app
COPY --from=backend-build /build/backend/target/lyradb-backend-*.jar app.jar

# H2 元数据库存 /app/data；动态下载的数据库驱动缓存存 /root/.lyradb（均建议挂卷持久化）
ENV SPRING_PROFILES_ACTIVE=prod \
    LYRADB_EDITION=personal
EXPOSE 8080
VOLUME ["/app/data", "/root/.lyradb"]

ENTRYPOINT ["java", "-Xmx512m", "-jar", "app.jar"]
