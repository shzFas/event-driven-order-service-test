# event-driven-order-service

[English](README.md) · **Čeština**

Mikroslužba pro zpracování objednávek postavená na událostech, v produkčním stylu.
Java 17, Spring Boot, Kafka, PostgreSQL — s idempotentními konzumenty, circuit
breakerem kolem nestabilní závislosti a testy, které běží proti skutečné
infrastruktuře.

```
POST /orders ──► orders ──► StockConsumer ──► orders.reserved ──► PaymentConsumer ──► orders.completed ──► NotificationConsumer
   PENDING                     RESERVED                              PAID / FAILED
```

## Spuštění

```bash
docker compose up --build
curl http://localhost:8080/actuator/health
```

Vytvoření objednávky a sledování jejího průchodu řetězcem:

```bash
curl -X POST http://localhost:8080/orders \
     -H 'Content-Type: application/json' \
     -d '{"customerId":"cust-1","orderReference":"ref-1","productId":"sku-1","quantity":2,"amount":99.90}'
# 202 Accepted, Location: /orders/{id}, stav PENDING

curl http://localhost:8080/orders/{id}
# o chvíli později stav PAID
```

Swagger UI běží na http://localhost:8080/swagger-ui.html. Podrobný návod, vývojový
režim a řešení problémů najdete v [SETUP.md](SETUP.md).

## Co projekt ukazuje

### Idempotentní konzumenti

Kafka doručuje zprávy alespoň jednou, takže každý konzument dříve či později uvidí
tutéž událost dvakrát. Neškodné to dělají dvě nezávislé vrstvy.

První patří databázi. `(customer_id, order_reference)` je unikátní byznysový klíč,
takže zopakovaný požadavek nemůže založit druhou objednávku — odmítne ho omezení
v databázi, ne aplikační kód, který by to musel stihnout zachytit.

Druhá patří doméně. Objednávka se posouvá pouze ze stavu, ve kterém skutečně je;
zopakování už provedeného přechodu nedělá nic a odchod z koncového stavu je
zakázán. Konzumenti kontrolují stav dřív, než začnou jednat, takže znovu doručená
událost nikdy nezarezervuje zboží podruhé ani podruhé nestrhne platbu.

Ověřeno na běžícím systému: opětovné odeslání naprosto stejné události
`OrderCreatedEvent` nechá objednávku ve stavu `PAID`, sklad snížený jen jednou
a v záznamech jedinou platbu.

### Odolnost

Platby procházejí přes circuit breaker z knihovny Resilience4j. Jakmile simulovaný
poskytovatel začne selhávat, obvod se rozpojí a další volání jsou okamžitě
odmítnuta, místo aby dále zatěžovala závislost, která už je mimo provoz.
Objednávky skončí ve stavu `FAILED`, místo aby navždy uvízly v `RESERVED`, a po
zotavení poskytovatele se obvod sám vrátí do stavu `CLOSED`.

Jak si to vyzkoušet:

```bash
APP_PAYMENT_FAILURE_RATE=1.0 docker compose up -d app
# vytvořte několik objednávek a poté:
curl http://localhost:8080/actuator/circuitbreakers
```

### Záruky doručení

Offsety se potvrzují ručně, až po úspěšném zpracování a po commitu jeho transakce —
pád uprostřed zpracování tedy událost přehraje znovu, místo aby ji ztratil. Chyby
se opakují s exponenciálním odstupem a poté se odkládají do topicu `.DLT`, protože
donekonečna opakovaná vadná zpráva by zablokovala svou partition a zdržela všechny
ostatní zákazníky, jejichž klíč do ní spadá.

Události se publikují až po commitu databázové transakce, takže se Kafka nikdy
nedozví o změně stavu, která byla vrácena zpět. Opačná mezera — pád mezi commitem
a publikováním — by vyžadovala transakční outbox a je záměrně mimo rozsah projektu.

### Rozdělení na partitions

Každá událost má klíč `customerId`. Kafka garantuje pořadí v rámci partition, takže
události jednoho zákazníka zůstávají v pořadí, zatímco různí zákazníci se zpracují
paralelně napříč partitions. Zákazníci partitions sdílejí, a to nevadí: potřebnou
zárukou je pořadí *uvnitř* zákazníka, nikoli *mezi* zákazníky.

## Testování

```bash
./mvnw test
```

43 testů. Jednotkové testy pokrývají doménové invarianty, servisní vrstvu s knihovnou
Mockito a HTTP kontrakt jako řez `@WebMvcTest`. Integrační testy běží proti
**skutečné Kafce a PostgreSQL**, které spouští Testcontainers — celý řetězec až do
`PAID`, cesta při nedostatku zboží, opětovné doručení události a poskytovatel plateb,
který odmítá každé volání.

Pro integrační testy musí běžet Docker.

## Rozhodnutí, která stojí za vysvětlení

**`202 Accepted`, nikoli `201 Created`.** Objednávka existuje, ale rezervace zboží
a platba proběhnou až následně, asynchronně. Odpověď `201` by tvrdila, že je práce
hotová, ačkoliv sotva začala.

**Duplicity se odmítají kódem `409`, místo vrácení existující objednávky.**
Byznysový klíč je zde chápán jako přirozený klíč, ne jako token idempotence.
Opačné chování je změna na jednom řádku v `OrderService.createOrder`.

**Konzumenti jsou tenké adaptéry nad službami.** Nejen kvůli přehlednosti:
transakce musí být potvrzena dřív než offset a volání `@Transactional` metody na
téže komponentě by obešlo proxy a tiše proběhlo bez transakce.

**Schéma patří Flywayi, nikdy ne Hibernate.** `ddl-auto` je nastaveno na
`validate`, takže mapování, které se rozejde s migrací, selže při startu, místo aby
potichu poškodilo data.

## Technologie

Java 17 · Spring Boot 4 · Spring for Apache Kafka · Spring Data JPA · PostgreSQL 16
· Flyway · Resilience4j · springdoc-openapi · Testcontainers · Docker Compose

## Struktura

```
src/main/java/com/yz/orderservice/
├── api/          REST vrstva, DTO, ošetření chyb dle RFC 9457
├── domain/       entita Order, životní cyklus stavů, repozitář
├── event/        události, producent, tři konzumenti
├── service/      orchestrace, sklad a platby, circuit breaker
└── config/       rozvržení topiců, politika selhání konzumentů
```

## Licence

[MIT](LICENSE) © 2026 Yuriy Zharlikov
