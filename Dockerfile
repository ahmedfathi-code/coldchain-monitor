# syntax=docker/dockerfile:1
#
# One multi-stage Dockerfile for every Spring Boot module. Pick the module with a build arg:
#   docker build --build-arg MODULE=ingestion-service -t coldchain/ingestion-service .
# infra/docker-compose.yml does this for each service.

# ---------------------------------------------------------------- Stage 1: build the jar
# Full JDK + the committed Maven wrapper (same Maven version as local builds).
FROM eclipse-temurin:21-jdk-alpine AS build
ARG MODULE
WORKDIR /workspace

# The Maven reactor needs the parent POM and the POM of every module listed in it.
COPY mvnw pom.xml ./
COPY .mvn .mvn
COPY common/pom.xml common/
COPY device-simulator/pom.xml device-simulator/
COPY ingestion-service/pom.xml ingestion-service/
COPY stream-processor/pom.xml stream-processor/
COPY api-service/pom.xml api-service/

# Sources: the shared library and the module being built.
COPY common/src common/src
COPY ${MODULE}/src ${MODULE}/src

# -pl builds only this module, -am ("also make") first builds what it depends on (common).
# Tests are skipped here: they run with "mvn verify" locally and in CI.
# The cache mount keeps Maven and the downloaded dependencies between builds.
# "sh mvnw" (not ./mvnw) still works if the executable bit was lost on a Windows checkout.
RUN --mount=type=cache,target=/root/.m2 \
    sh mvnw -B -q -pl "${MODULE}" -am package -DskipTests \
    && cp "${MODULE}"/target/"${MODULE}"-*.jar /workspace/app.jar

# ---------------------------------------------------------------- Stage 2: run the jar
# Only a JRE and the jar: a much smaller image without build tools or sources.
FROM eclipse-temurin:21-jre-alpine

# Do not run as root inside the container.
RUN addgroup -S app && adduser -S app -G app
USER app

WORKDIR /app
COPY --from=build /workspace/app.jar app.jar

# The heap may use up to 75 % of the memory available to the container.
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
