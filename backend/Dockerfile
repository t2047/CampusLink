# ─── CampusLink Chat Backend (Spring Boot 4.1) ───
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY backend/pom.xml backend/mvnw backend/mvnw.cmd ./
COPY backend/.mvn ./.mvn
RUN chmod +x mvnw && ./mvnw -Pfast-sast -DskipTests dependency:go-offline -B

COPY backend/src ./src
RUN ./mvnw -Pfast-sast -DskipTests package -B

# ─── Runtime ───
FROM eclipse-temurin:21-jre-alpine

# Non-root user
RUN addgroup -S campus && adduser -S campus -G campus
USER campus

COPY --from=builder /app/target/*.jar /app/app.jar

# RSA 密钥目录：预创建并归属 campus 用户（命名卷 delegation_keys 首次挂载时
# 继承镜像目录 owner，否则卷属 root → campus 无法写入 → AccessDenied）
RUN mkdir -p /app/keys && chown campus:campus /app/keys

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
