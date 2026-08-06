# ─── CampusLink Chat Backend (Spring Boot 4.1) ───
FROM eclipse-temurin:21-jdk-alpine AS builder

WORKDIR /app
COPY backend/pom.xml backend/mvnw backend/mvnw.cmd ./
COPY backend/.mvn ./.mvn
RUN chmod +x mvnw && ./mvnw dependency:go-offline -B

COPY backend/src ./src
RUN ./mvnw package -DskipTests -B

# ─── Runtime ───
FROM eclipse-temurin:21-jre-alpine

# Non-root user
RUN addgroup -S campus && adduser -S campus -G campus
USER campus

COPY --from=builder /app/target/*.jar /app/app.jar

HEALTHCHECK --interval=30s --timeout=5s --retries=3 \
  CMD wget -qO- http://localhost:8080/actuator/health || exit 1

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
