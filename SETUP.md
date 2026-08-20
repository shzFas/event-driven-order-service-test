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

## Generating load

`scripts/loadgen.py` keeps placing orders until you stop it. It uses nothing but
the Python 3 standard library, so there is no virtualenv to set up:

```bash
python3 scripts/loadgen.py                             # 10 orders/s, until Ctrl-C
python3 scripts/loadgen.py --rate 50 --concurrency 16
python3 scripts/loadgen.py --rate 0 --concurrency 32   # unthrottled
python3 scripts/loadgen.py --duration 120 --ramp 30    # two minutes, ramping up
python3 scripts/loadgen.py --help                      # every knob
```

The same thing without Python on the host — a compose profile, so it stays out of
the way of a normal `docker compose up`:

```bash
LOADGEN_RATE=50 docker compose --profile load up -d loadgen
docker compose logs -f loadgen
docker compose --profile load down       # or: docker compose stop loadgen
```

Progress lines report send rate, response codes and `POST /orders` percentiles;
a sample of orders is polled until it reaches `PAID` or `FAILED`, which measures
the whole pipeline from outside the service. `409`s are expected — a share of the
traffic replays a business key on purpose.

| Variable                     | Flag                   | Default                 |
| ---------------------------- | ---------------------- | ----------------------- |
| `LOADGEN_URL`                | `--url`                | `http://localhost:8080` |
| `LOADGEN_RATE`               | `--rate`               | `10` (`0` = unthrottled) |
| `LOADGEN_CONCURRENCY`        | `--concurrency`        | `8`                     |
| `LOADGEN_DURATION`           | `--duration`           | `0` (forever)           |
| `LOADGEN_RAMP`               | `--ramp`               | `0`                     |
| `LOADGEN_PRODUCTS`           | `--products`           | `8`                     |
| `LOADGEN_UNAVAILABLE_SHARE`  | `--unavailable-share`  | `0.02`                  |
| `LOADGEN_DUPLICATE_SHARE`    | `--duplicate-share`    | `0.02`                  |
| `LOADGEN_FOLLOW_SHARE`       | `--follow-share`       | `0.05`                  |

Two things to keep in mind when pushing hard. Stock is held in memory and never
replenished, so a long run drains it and every further order for that product
fails — `docker-compose.yml` therefore starts the app with a generous
`APP_STOCK_UNITS_PER_PRODUCT`, and the **Stock remaining** panel shows what is
left. And consumers are the narrow part of the pipe: `POST /orders` is one insert
and one buffered publish, while every order downstream waits on the simulated
payment provider. Sending faster than the consumers drain is not a bug — it is the
backlog the dashboard exists to show.

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
| `SPRING_KAFKA_LISTENER_CONCURRENCY` | `3` — consumer threads per listener, capped by the partition count |
| `APP_STOCK_UNITS_PER_PRODUCT`    | `100` in the app, `1000000` in Compose     |
| `APP_PAYMENT_FAILURE_RATE`       | `0.0` — set to `1.0` to trip the circuit breaker |
| `APP_PAYMENT_LATENCY_MILLIS`     | `50` — how long a simulated charge takes   |

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

**The pipeline panels are empty but the JVM ones work.** `orders_*` and
`stock_available_units` only exist once an order has passed through since the
application started — they are created by the events, not declared up front. Place
an order, or start the load generator, and they appear on the next scrape.

**Lag panels are empty but the rest of the dashboard works.** That is
`kafka-exporter`. It talks to the broker with a pinned protocol version
(`--kafka.version` in `docker-compose.yml`); if you upgrade the Kafka image far
enough, the exporter may need a newer tag or a different version there. Check
`docker compose logs kafka-exporter`. The application's own client-side lag panel
keeps working regardless.
