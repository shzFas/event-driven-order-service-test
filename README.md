# Flagship-проект — гид по сборке (для тебя, не в репозиторий)

## Название репозитория
`event-driven-order-service`

Описание репо (GitHub "About"):
> Production-style event-driven order-processing microservice — Java 17, Spring Boot, Kafka, PostgreSQL. Idempotent consumers, resilience, full test suite, AI-first workflow.

Topics/теги на GitHub (важно для поиска): `java` `spring-boot` `kafka` `microservices` `event-driven` `postgresql` `docker` `resilience4j` `testcontainers`

---

## Структура пакетов (Maven, стандарт "по ГОСТу")

```
event-driven-order-service/
├── README.md                       ← (готов, EN/CZ)
├── LICENSE                         ← MIT
├── .gitignore                     ← target/, *.iml, .idea/, .env
├── docker-compose.yml             ← kafka + zookeeper + postgres + app
├── Dockerfile                     ← multi-stage build
├── pom.xml
└── src/
    ├── main/
    │   ├── java/com/yz/orderservice/
    │   │   ├── OrderServiceApplication.java
    │   │   ├── api/
    │   │   │   ├── OrderController.java          ← POST /orders, GET /orders/{id}
    │   │   │   ├── dto/OrderRequest.java         ← record
    │   │   │   └── dto/OrderResponse.java        ← record
    │   │   ├── domain/
    │   │   │   ├── Order.java                    ← @Entity
    │   │   │   ├── OrderStatus.java              ← enum (PENDING, RESERVED, PAID, FAILED)
    │   │   │   └── OrderRepository.java          ← JpaRepository
    │   │   ├── event/
    │   │   │   ├── OrderCreatedEvent.java        ← record
    │   │   │   ├── OrderEventProducer.java       ← KafkaTemplate, partition by customerId
    │   │   │   ├── StockConsumer.java            ← @KafkaListener, idempotent upsert
    │   │   │   └── PaymentConsumer.java          ← @KafkaListener + Resilience4j circuit breaker
    │   │   ├── service/
    │   │   │   └── OrderService.java             ← validate, save, publish
    │   │   └── config/
    │   │       ├── KafkaConfig.java
    │   │       └── CorrelationIdFilter.java      ← observability
    │   └── resources/
    │       ├── application.yml
    │       └── db/migration/
    │           └── V1__create_orders.sql        ← Flyway
    └── test/
        └── java/com/yz/orderservice/
            ├── OrderServiceTest.java             ← unit, Mockito
            └── OrderFlowIntegrationTest.java     ← Testcontainers: real Kafka + Postgres
```

---

## Порядок сборки за 1 день (с AI-ассистентом)

**Шаг 1 (30 мин):** `spring init` через start.spring.io — зависимости: Web, Spring for Apache Kafka, Spring Data JPA, PostgreSQL Driver, Flyway, Actuator, Validation, Resilience4j, Testcontainers, springdoc-openapi. Java 17, Maven.

**Шаг 2 (1 ч):** domain + repository + Flyway-миграция (таблица orders с unique constraint на business key для идемпотентности).

**Шаг 3 (1.5 ч):** OrderController + OrderService + producer. POST принимает заказ → сохраняет PENDING → публикует OrderCreatedEvent в Kafka с ключом = customerId.

**Шаг 4 (1.5 ч):** консьюмеры (Stock, Payment). Idempotent upsert. На Payment — Resilience4j circuit breaker вокруг симулированного "внешнего" вызова.

**Шаг 5 (1 ч):** тесты. Unit на OrderService (Mockito), integration на весь flow (Testcontainers поднимает реальные Kafka+Postgres).

**Шаг 6 (1 ч):** docker-compose (kafka, zookeeper, postgres, app), Dockerfile multi-stage, финальная проверка `docker compose up`.

**Шаг 7 (30 мин):** README (готов), LICENSE, чистка, коммиты осмысленными шагами.

---

## КРИТИЧНО для "не воздушного" резюме

1. **Коммиты — историей, не одним "initial commit".** Делай коммиты по шагам: "add domain model", "add Kafka producer", "add idempotent stock consumer", "add integration tests". Это показывает КАК ты работаешь. Рекрутер смотрит commit history.

2. **Каждый коммит с осмысленным сообщением** на английском: `feat: idempotent stock consumer with upsert by business key`.

3. **Тесты ДОЛЖНЫ быть и проходить.** Backend-инженер без тестов в pet-проекте = красный флаг. Хотя бы 1 unit + 1 integration.

4. **`docker compose up` должен работать с первого раза.** Ревьюер запускает за 30 секунд — если не заводится, впечатление испорчено.

5. **Pinned на профиле GitHub** — закрепи этот репозиторий наверху (Customize your pins).

6. **Не заливай сырое.** Лучше потратить лишний час на чистоту, чем залить с TODO и закомментированным кодом.

---

## Если успеваешь второй проект (AI-first showcase)

`ai-document-analyzer` — Spring Boot сервис: принимает текст → отправляет в **локальную LLM через Ollama** → возвращает структурированный JSON (например, извлечение сущностей). README подчёркивает: "локальная модель, а не внешний API — для приватности данных". Это твой уникальный дифференциатор, мало кто показывает работу с локальными LLM.

Но: **один вылизанный проект > два сырых.** Если на второй не хватает времени/качества — оставь один flagship, он сильнее пустого профиля с двумя недоделками.