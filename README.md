# event-driven-order-service

**English** · [Čeština](README.cs.md)

Production-style event-driven order-processing microservice. Java 17, Spring Boot,
Kafka, PostgreSQL — with idempotent consumers, a circuit breaker around an unstable
dependency, and a test suite that runs against real infrastructure.

```
POST /orders ──► orders ──► StockConsumer ──► orders.reserved ──► PaymentConsumer ──► orders.completed ──► NotificationConsumer
   PENDING                     RESERVED                              PAID / FAILED
```

## Run it

```bash
docker compose up --build
curl http://localhost:8080/actuator/health
```

Place an order and watch it move through the chain:

```bash
curl -X POST http://localhost:8080/orders \
     -H 'Content-Type: application/json' \
     -d '{"customerId":"cust-1","orderReference":"ref-1","productId":"sku-1","quantity":2,"amount":99.90}'
# 202 Accepted, Location: /orders/{id}, status PENDING

curl http://localhost:8080/orders/{id}
# status PAID, a moment later
```

Swagger UI is at http://localhost:8080/swagger-ui.html, and the Grafana dashboard —
throughput, consumer lag, circuit breaker — at http://localhost:3000. Full
instructions, development mode and troubleshooting are in [SETUP.md](SETUP.md).

## What this project demonstrates

### Idempotent consumers

Kafka delivers at least once, so every consumer will eventually see the same event
twice. Two independent layers make that harmless.

The database owns the first one. `(customer_id, order_reference)` is a unique
business key, so a retried request cannot create a second order — the constraint
rejects it rather than application code hoping to catch it in time.

The domain owns the second. An order only moves forward from the status it is
actually in; re-applying a transition that already happened is a no-op, and moving
out of a terminal status is refused. Consumers check status before acting, so a
redelivered event never reserves stock twice or charges a customer twice.

Verified end to end: replaying the exact same `OrderCreatedEvent` on a running
stack leaves the order `PAID`, stock decremented once, and one charge on record.

### Resilience

Payments go through a Resilience4j circuit breaker. Once the simulated provider
starts failing, the breaker opens and further calls are rejected immediately
instead of piling onto a dependency that is already down. Orders resolve to
`FAILED` rather than hanging in `RESERVED` forever, and the breaker returns to
`CLOSED` on its own once the provider recovers.

Watch it happen:

```bash
APP_PAYMENT_FAILURE_RATE=1.0 docker compose up -d app
# place a few orders, then:
curl http://localhost:8080/actuator/circuitbreakers
```

### Delivery guarantees

Offsets are committed manually, only after processing has succeeded and its
transaction has committed — a crash mid-processing replays the event instead of
losing it. Failures are retried with exponential backoff and then parked in a
`.DLT` topic, because retrying a poison message forever would block its partition
and stall every other customer whose key hashes to it.

Events are published after the database transaction commits, so Kafka never learns
about a state change that was rolled back. The reverse gap — a crash between commit
and publish — would need a transactional outbox and is deliberately out of scope.

### Partitioning

Every event is keyed by `customerId`. Kafka guarantees ordering within a partition,
so one customer's events stay in sequence while different customers are processed
in parallel across partitions. Customers share partitions, which is fine: the
guarantee needed here is order *within* a customer, not *between* them.

### Observability

Actuator exposes Micrometer's meters at `/actuator/prometheus`, Prometheus scrapes
them every five seconds, and Grafana draws them. The datasource and the dashboard
are provisioned from `monitoring/`, so the stack comes up already wired — there is
nothing to import by hand and no clicking through a setup wizard.

| URL                   | What                                            |
| --------------------- | ----------------------------------------------- |
| http://localhost:3000 | Grafana — the **Order Service** dashboard       |
| http://localhost:9090 | Prometheus — raw queries and scrape target health |

Consumer lag is measured twice, on purpose. The Kafka client inside the JVM reports
`kafka_consumer_fetch_manager_records_lag`: cheap, accurate, and silent exactly when
the application is down — which is when lag matters most. `kafka-exporter` asks the
broker instead (`kafka_consumergroup_lag`), so the number outlives the thing being
measured. Both are on the dashboard, side by side.

Boot's own meters describe the plumbing — requests served, records consumed — but
not the thing the service is for. These are added on top, in `metrics/`:

| Metric                       | What it answers                                      |
| ---------------------------- | ---------------------------------------------------- |
| `orders_accepted_total`, `orders_reserved_total`, `orders_completed_total` | How far orders get, and how many end `PAID` or `FAILED` |
| `orders_pipeline_seconds`    | Accepted → terminal, the whole chain, as a histogram  |
| `orders_stage_seconds`       | The same split into reserve and payment, so a slowdown has an address |
| `orders_current`             | Orders sitting in each status right now               |
| `stock_available_units`      | Units left per product, which explains a wave of `FAILED` |

`orders_completed_total` carries a bucketed `reason` rather than the raw failure
text — a tag value that varies per order would create a time series per order.

### Load

`scripts/loadgen.py` places orders continuously, so the dashboard has something to
show. Standard library only, nothing to install:

```bash
python3 scripts/loadgen.py --rate 50            # 50 orders/s until Ctrl-C
python3 scripts/loadgen.py --rate 0 --concurrency 32   # as fast as it goes
```

Or from the compose stack, which needs no Python on the host:

```bash
LOADGEN_RATE=50 docker compose --profile load up -d loadgen
docker compose logs -f loadgen
```

The traffic is deliberately not uniform: a small share asks for the SKU that can
never be reserved, and another share replays a business key that was already used.
The failure path and the idempotency guard therefore get exercised by the same run
that measures throughput — the `409`s in the output are the unique constraint doing
its job. A sample of orders is polled to completion, so the run also reports
end-to-end latency measured from outside the service.

```
 elapsed     target      sent      rps      202     409     4xx     5xx     err      p50      p95      p99
     15s      50.0/s       750     50.0      735      15       0       0       0       3ms      5ms      7ms
         followed so far: FAILED 1  PAID 36 | awaiting 2
```

What a run makes visible: the API accepts orders far faster than the chain can
finish them — a few thousand a second, since `POST /orders` is one insert and one
buffered publish — and everything downstream is paced by the payment provider's
simulated fifty milliseconds. Push past what the consumers can drain and the backlog
appears on the dashboard stage by stage: `orders_current` climbs on `PENDING`, then
on `RESERVED`, consumer lag follows one topic behind, and end-to-end latency goes
from milliseconds to tens of seconds while `POST /orders` stays fast. Nothing is
lost — the backlog drains and the orders end `PAID`, which is the point of putting a
queue between the stages.

The knobs worth turning are `app.kafka.partitions` and
`spring.kafka.listener.concurrency` (consumer parallelism, three each by default),
`app.payment.latency-millis` (how expensive a charge is), and
`APP_PAYMENT_FAILURE_RATE` (how often it fails). The same dashboard shows the
circuit breaker flipping to `open` under `APP_PAYMENT_FAILURE_RATE=1.0`, `POST
/orders` latency percentiles computed by Prometheus from histogram buckets rather
than averaged from per-instance summaries, and anything parked in a `.DLT` topic.

## Testing

```bash
./mvnw test
```

48 tests. Unit tests cover domain invariants, the service layer with Mockito, and
the HTTP contract as a `@WebMvcTest` slice. Integration tests run against **real
Kafka and PostgreSQL** started by Testcontainers — the whole chain to `PAID`, the
out-of-stock path, event redelivery, and a payment provider that rejects every
call.

Docker must be running for the integration tests.

## Design decisions worth explaining

**`202 Accepted`, not `201 Created`.** The order exists, but stock reservation and
payment happen asynchronously afterwards. Promising `201` would claim the work is
finished when it has barely started.

**Rejecting duplicates with `409`, not returning the existing order.** The business
key is treated as a natural key rather than an idempotency token. Returning the
existing order instead is a one-line change in `OrderService.createOrder`.

**Consumers are thin adapters over services.** Not only for tidiness: the
transaction must commit before the offset does, and calling a `@Transactional`
method on the same bean would bypass the proxy and silently run without one.

**Schema belongs to Flyway, never to Hibernate.** `ddl-auto` is `validate`, so a
mapping that drifts from the migration fails at startup instead of quietly
corrupting data.

## Stack

Java 17 · Spring Boot 4 · Spring for Apache Kafka · Spring Data JPA · PostgreSQL 16
· Flyway · Resilience4j · Micrometer · Prometheus · Grafana · springdoc-openapi ·
Testcontainers · Docker Compose

## Layout

```
src/main/java/com/yz/orderservice/
├── api/          REST layer, DTOs, RFC 9457 error handling
├── domain/       Order entity, status lifecycle, repository
├── event/        Events, producer, three consumers
├── service/      Orchestration, stock and payment, circuit breaker
├── metrics/      Pipeline metrics, kept out of the services they measure
└── config/       Topic layout, consumer failure policy

monitoring/       Prometheus scrape config, provisioned Grafana datasource and dashboard
scripts/          Load generator
```

## License

[MIT](LICENSE) © 2026 Yuriy Zharlikov
