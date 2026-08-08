FROM eclipse-temurin:21-jdk AS builder

WORKDIR /workspace
COPY .mvn .mvn
COPY mvnw pom.xml ./
RUN chmod +x mvnw \
    && ./mvnw -Pfast-sast -DskipTests dependency:go-offline
COPY src src
RUN ./mvnw -Pfast-sast -DskipTests package

FROM eclipse-temurin:21-jre

RUN groupadd --system campuslink \
    && useradd --system --gid campuslink --create-home campuslink

WORKDIR /app
COPY --from=builder /workspace/target/*.jar /app/app.jar

USER campuslink
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
