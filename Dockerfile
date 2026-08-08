# ---- build stage ----
FROM eclipse-temurin:17-jdk-noble AS build
WORKDIR /build

# Dependencies are resolved in their own layer so that source changes
# do not invalidate the (slow) download step.
COPY .mvn/ .mvn/
COPY mvnw pom.xml ./
RUN ./mvnw -B -q dependency:go-offline

COPY src/ src/
RUN ./mvnw -B -q -DskipTests package

# ---- runtime stage ----
FROM eclipse-temurin:17-jre-noble
WORKDIR /app

# curl is used by the container healthcheck in docker-compose.yml
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin app
COPY --from=build --chown=app:app /build/target/*.jar app.jar
USER app

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
