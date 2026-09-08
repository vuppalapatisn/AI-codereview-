FROM eclipse-temurin:21-jdk-alpine AS build
WORKDIR /app
COPY pom.xml .
COPY .mvn/ .mvn
COPY mvnw .
RUN --mount=type=cache,target=/root/.m2 chmod +x mvnw && ./mvnw -B dependency:go-offline
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B -DskipTests package

FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S review && adduser -S review -G review
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar
COPY conventions/ /app/conventions/
USER review
EXPOSE 8080
ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
