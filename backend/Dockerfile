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
# RSA 密钥持久化目录：预建并赋予 campus 属主（DELEGATION_KEY_DIR=/app/keys，
# 命名卷首次挂载会继承镜像目录属主，避免 root 属主导致非 root 用户写权限拒绝）
RUN mkdir -p /app/keys && chown -R campus:campus /app/keys
USER campus

COPY --from=builder /app/target/*.jar /app/app.jar

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
