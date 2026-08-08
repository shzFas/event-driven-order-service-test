package com.yz.orderservice;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.yz.orderservice.api.dto.OrderRequest;
import com.yz.orderservice.api.dto.OrderResponse;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Runs the flow against a payment provider that rejects every call, and asserts the
 * two things that matter when a dependency is down: no order is left hanging, and
 * the provider stops being called at all once the breaker opens.
 *
 * <p>The open state is held for a minute here so the assertions cannot race a
 * transition back to half-open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = { "app.payment.failure-rate=1.0", "app.payment.latency-millis=0",
		"resilience4j.circuitbreaker.instances.payment.wait-duration-in-open-state=60s" })
class PaymentResilienceIntegrationTest {

	private static final Duration TIMEOUT = Duration.ofSeconds(30);

	@Autowired
	private TestRestTemplate restTemplate;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private CircuitBreakerRegistry circuitBreakerRegistry;

	@Test
	void brokenProviderOpensTheBreakerAndStillResolvesEveryOrder() {
		CircuitBreaker breaker = this.circuitBreakerRegistry.circuitBreaker("payment");
		long notPermittedBefore = breaker.getMetrics().getNumberOfNotPermittedCalls();

		List<UUID> orderIds = new ArrayList<>();
		for (int i = 0; i < 12; i++) {
			OrderRequest request = new OrderRequest("cust-" + UUID.randomUUID(), "ref-1", "sku-1", 1,
					new BigDecimal("5.00"));
			OrderResponse response = this.restTemplate.postForEntity("/orders", request, OrderResponse.class)
				.getBody();
			assertThat(response).isNotNull();
			orderIds.add(response.id());
		}

		// Every order reaches a terminal state. None sits in RESERVED waiting for a
		// payment that will never come.
		await().atMost(TIMEOUT).untilAsserted(() -> {
			for (UUID orderId : orderIds) {
				assertThat(status(orderId)).isEqualTo(OrderStatus.FAILED);
			}
		});

		assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

		// Proof the breaker did its job: later calls were rejected outright instead
		// of being passed to a provider that is already known to be down.
		assertThat(breaker.getMetrics().getNumberOfNotPermittedCalls()).isGreaterThan(notPermittedBefore);
	}

	private OrderStatus status(UUID orderId) {
		return this.orderRepository.findById(orderId).orElseThrow().getStatus();
	}

}
