# Setup

How to build and run `order-service` locally.

## Prerequisites

| Tool   | Version | Notes                                              |
| ------ | ------- | -------------------------------------------------- |
| JDK    | 17+     | Required only for running the app outside Docker    |
| Docker | 20.10+  | With Compose v2 (`docker compose`, not `docker-compose`) |

Maven is **not** required — the repository ships the Maven Wrapper (`./mvnw`).

Verify your setup:

```bash
java -version
docker compose version
```

## Getting the code

```bash
git clone https://github.com/shzFas/event-driven-order-service.git
cd event-driven-order-service
```

## Quick start

Everything in containers — this is the fastest way to see the service running:

```bash
docker compose up --build
```

The stack starts in dependency order: PostgreSQL and Kafka first, then the
application once both report healthy. First run takes a few minutes while the
base images are pulled and Maven downloads dependencies.

Verify it is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP", ...}
```

Then stop it:

```bash
docker compose down      # stop containers, keep database data
docker compose down -v   # stop containers and wipe the database volume
```

## Development mode

Run infrastructure in Docker, run the application from your IDE or terminal.
This gives you fast restarts and a debugger.

```bash
# 1. Start Postgres and Kafka only
docker compose up -d postgres kafka

# 2. Run the application on the host
./mvnw spring-boot:run
```

The defaults in `application.yml` already point at the containers
(`localhost:5432` for Postgres, `localhost:29092` for Kafka), so no extra
configuration is needed.

## Endpoints

| URL                                        | Description                       |
| ------------------------------------------ | --------------------------------- |
| http://localhost:8080/actuator/health       | Health check                      |
| http://localhost:8080/actuator/metrics      | Application metrics               |
| http://localhost:8080/actuator/prometheus   | The same metrics, Prometheus format |
| http://localhost:8080/swagger-ui.html       | Swagger UI                        |
| http://localhost:8080/v3/api-docs           | OpenAPI specification             |
| http://localhost:3000                       | Grafana — **Order Service** dashboard |
| http://localhost:9090                       | Prometheus — queries, target health |
| http://localhost:9308/metrics               | kafka-exporter — broker-side lag  |

Grafana allows anonymous access with the Admin role, so the dashboard opens
without a login; `admin` / `admin` still works if you want to sign in. Both the
Prometheus datasource and the dashboard are provisioned from `monitoring/` — edits
made in the UI live only until the container is recreated, so change the JSON in
the repository instead.

## Build and test

```bash
./mvnw package          # compile, run tests, build the jar
./mvnw test             # run tests only
./mvnw -DskipTests package   # build without tests
```

Integration tests use Testcontainers, which starts real Kafka and PostgreSQL
containers. **Docker must be running** or those tests will fail.

The resulting artifact is `target/order-service-0.0.1-SNAPSHOT.jar` and can be
run directly:

```bash
java -jar target/order-service-0.0.1-SNAPSHOT.jar
```

## Ports

| Port    | Service                                                  |
| ------- | -------------------------------------------------------- |
| `8080`  | Application (HTTP)                                       |
| `5432`  | PostgreSQL                                               |
| `29092` | Kafka, host-facing listener                              |
| `9092`  | Kafka, internal listener — container-to-container only   |
| `3000`  | Grafana                                                  |
| `9090`  | Prometheus                                               |
| `9308`  | kafka-exporter                                           |

Kafka advertises two listeners. Containers on the Compose network reach the
broker at `kafka:9092`; processes on the host must use `localhost:29092`.

## Configuration

Defaults live in `src/main/resources/application.yml` and can be overridden
with environment variables — this is how `docker-compose.yml` points the app at
the containers instead of `localhost`:

| Variable                         | Default                                    |
| -------------------------------- | ------------------------------------------ |
| `SPRING_DATASOURCE_URL`          | `jdbc:postgresql://localhost:5432/orders`  |
| `SPRING_DATASOURCE_USERNAME`     | `orders`                                   |
| `SPRING_DATASOURCE_PASSWORD`     | `orders`                                   |
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | `localhost:29092`                          |

Database schema is managed by Flyway (`src/main/resources/db/migration`) and
applied automatically on startup. Hibernate runs with `ddl-auto: validate`, so
the schema is never modified by the ORM — migrations are the only source of
truth.

## Troubleshooting

**Port already in use.** Something else is bound to `8080`, `5432`, or `29092`.
Find it with `lsof -i :5432`, or change the host-side port mapping in
`docker-compose.yml`.

**`Connection refused` to Kafka when running from the IDE.** You are most
likely using `localhost:9092`. From the host the broker is on `localhost:29092`;
`9092` is the internal listener and is not published.

**Tests fail with `Could not find a valid Docker environment`.** Docker is not
running. Start Docker Desktop and retry.

**Application container keeps restarting.** Inspect the logs and the health of
its dependencies:

```bash
docker compose logs app
docker compose ps
```

**Stale database state.** Drop the volume and start clean:

```bash
docker compose down -v && docker compose up --build
```

**Grafana panels say "No data".** Check what Prometheus thinks of its targets at
http://localhost:9090/targets. The application job scrapes `app:8080` over the
Compose network, so it goes down whenever the `app` container is not running —
including in development mode, where the application runs on the host instead. To
scrape a host-run application, point the `order-service` job in
`monitoring/prometheus.yml` at `host.docker.internal:8080`.

**Lag panels are empty but the rest of the dashboard works.** That is
`kafka-exporter`. It talks to the broker with a pinned protocol version
(`--kafka.version` in `docker-compose.yml`); if you upgrade the Kafka image far
enough, the exporter may need a newer tag or a different version there. Check
`docker compose logs kafka-exporter`. The application's own client-side lag panel
keeps working regardless.
