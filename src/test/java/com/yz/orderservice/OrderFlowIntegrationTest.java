package com.yz.orderservice;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.yz.orderservice.api.dto.OrderRequest;
import com.yz.orderservice.api.dto.OrderResponse;
import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCreatedEvent;
import com.yz.orderservice.service.StockLedger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises the whole chain against real Kafka and PostgreSQL containers:
 * HTTP → database → Kafka → StockConsumer → Kafka → PaymentConsumer → database.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "app.payment.failure-rate=0.0", "app.payment.latency-millis=0" })
class OrderFlowIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private StockLedger stockLedger;

	@Autowired
	private KafkaTemplate<String, Object> kafkaTemplate;

	@Value("${app.kafka.topics.orders}")
	private String ordersTopic;

	@BeforeEach
	void resetStock() {
		this.stockLedger.reset();
	}

	@Test
	void orderReachesPaidThroughTheWholeChain() {
		OrderRequest request = new OrderRequest(customer(), "ref-1", "sku-1", 2, new BigDecimal("99.90"));

		ResponseEntity<OrderResponse> response = this.restTemplate.postForEntity("/orders", request,
				OrderResponse.class);

		assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
		assertThat(response.getBody()).isNotNull();
		assertThat(response.getBody().status()).isEqualTo(OrderStatus.PENDING);

		UUID orderId = response.getBody().id();
		await().atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(status(orderId)).isEqualTo(OrderStatus.PAID));

		// The order passed through RESERVED on its way here, so stock was taken once.
		assertThat(this.stockLedger.available("sku-1")).isEqualTo(98);
	}

	@Test
	void orderWithoutStockEndsFailed() {
		OrderRequest request = new OrderRequest(customer(), "ref-1", "sku-unavailable", 1, new BigDecimal("10.00"));

		ResponseEntity<OrderResponse> response = this.restTemplate.postForEntity("/orders", request,
				OrderResponse.class);
		UUID orderId = response.getBody().id();

		await().atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(status(orderId)).isEqualTo(OrderStatus.FAILED));
	}

	/**
	 * The point of the whole design: replaying an event must not move the order on
	 * again or take stock a second time.
	 */
	@Test
	void redeliveredEventIsANoOp() {
		OrderRequest request = new OrderRequest(customer(), "ref-1", "sku-1", 3, new BigDecimal("30.00"));

		ResponseEntity<OrderResponse> response = this.restTemplate.postForEntity("/orders", request,
				OrderResponse.class);
		UUID orderId = response.getBody().id();

		await().atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(status(orderId)).isEqualTo(OrderStatus.PAID));

		int stockAfterFirstPass = this.stockLedger.available("sku-1");
		assertThat(stockAfterFirstPass).isEqualTo(97);

		// Replay exactly what the producer sent the first time.
		Order order = this.orderRepository.findById(orderId).orElseThrow();
		this.kafkaTemplate.send(this.ordersTopic, order.getCustomerId(),
				new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getOrderReference(),
						order.getProductId(), order.getQuantity(), order.getAmount(), Instant.now()));

		// Give the consumer time to process the replay and prove nothing moved.
		await().pollDelay(Duration.ofSeconds(3))
			.atMost(TIMEOUT)
			.untilAsserted(() -> assertThat(status(orderId)).isEqualTo(OrderStatus.PAID));
		assertThat(this.stockLedger.available("sku-1")).isEqualTo(stockAfterFirstPass);
	}

	private OrderStatus status(UUID orderId) {
		return this.orderRepository.findById(orderId).orElseThrow().getStatus();
	}

	/**
	 * A fresh customer per test keeps the business key unique, so tests stay
	 * independent of each other's data.
	 */
	private String customer() {
		return "cust-" + UUID.randomUUID();
	}

}
