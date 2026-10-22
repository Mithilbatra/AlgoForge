FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -q dependency:go-offline
COPY src src
RUN mvn -q -DskipTests package && cp target/algoforge-*.jar /app.jar

FROM eclipse-temurin:21-jre AS api
RUN useradd --system --uid 10001 app
COPY --from=build /app.jar /app.jar
USER app
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app.jar"]

# The worker needs the docker CLI to start sandbox containers.
FROM api AS worker
USER root
COPY --from=docker:27-cli /usr/local/bin/docker /usr/local/bin/docker
ENTRYPOINT ["java", "-jar", "/app.jar", "--spring.profiles.active=worker"]
