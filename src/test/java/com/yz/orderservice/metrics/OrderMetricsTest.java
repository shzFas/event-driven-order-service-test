package com.yz.orderservice.metrics;

import java.math.BigDecimal;
import java.util.UUID;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCompletedEvent;
import com.yz.orderservice.event.OrderCreatedEvent;
import com.yz.orderservice.event.OrderReservedEvent;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OrderMetricsTest {

	private final MeterRegistry registry = new SimpleMeterRegistry();

	private final OrderMetrics metrics = new OrderMetrics(this.registry);

	private static Order orderIn(OrderStatus status) {
		Order order = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		if (status != OrderStatus.PENDING) {
			order.transitionTo(status);
		}
		return order;
	}

	@Test
	void countsEveryStageOfASuccessfulOrder() {
		Order order = orderIn(OrderStatus.PENDING);
		this.metrics.onOrderEvent(OrderCreatedEvent.from(order));
		order.transitionTo(OrderStatus.RESERVED);
		this.metrics.onOrderEvent(OrderReservedEvent.from(order));
		order.transitionTo(OrderStatus.PAID);
		this.metrics.onOrderEvent(OrderCompletedEvent.from(order, null));

		assertThat(this.registry.counter("orders.accepted").count()).isEqualTo(1);
		assertThat(this.registry.counter("orders.reserved").count()).isEqualTo(1);
		assertThat(this.registry.counter("orders.completed", "status", "PAID", "reason", "none").count()).isEqualTo(1);
		assertThat(this.registry.timer("orders.stage", "stage", "reserve").count()).isEqualTo(1);
		assertThat(this.registry.timer("orders.stage", "stage", "payment").count()).isEqualTo(1);
		assertThat(this.registry.timer("orders.pipeline", "status", "PAID").count()).isEqualTo(1);
	}

	@Test
	void bucketsTheFailureReason() {
		Order order = orderIn(OrderStatus.PENDING);
		this.metrics.onOrderEvent(OrderCreatedEvent.from(order));
		order.transitionTo(OrderStatus.FAILED);
		this.metrics.onOrderEvent(OrderCompletedEvent.from(order, "Insufficient stock"));

		assertThat(this.registry.counter("orders.completed", "status", "FAILED", "reason", "insufficient_stock")
			.count()).isEqualTo(1);
		// It never got reserved, so only the end-to-end timer has a sample.
		assertThat(this.registry.timer("orders.stage", "stage", "payment").count()).isZero();
		assertThat(this.registry.timer("orders.pipeline", "status", "FAILED").count()).isEqualTo(1);
	}

	@Test
	void keepsAnyFailureMessageOutOfTheTagValue() {
		Order order = orderIn(OrderStatus.RESERVED);
		order.transitionTo(OrderStatus.FAILED);
		this.metrics
			.onOrderEvent(OrderCompletedEvent.from(order, "Payment provider is unavailable for " + UUID.randomUUID()));

		assertThat(this.registry.counter("orders.completed", "status", "FAILED", "reason", "payment_declined").count())
			.isEqualTo(1);
	}

	@Test
	void stopsTrackingAnOrderOnceItIsDone() {
		Order order = orderIn(OrderStatus.PENDING);
		this.metrics.onOrderEvent(OrderCreatedEvent.from(order));
		assertThat(this.registry.get("orders.tracked").gauge().value()).isEqualTo(1);

		order.transitionTo(OrderStatus.RESERVED);
		order.transitionTo(OrderStatus.PAID);
		this.metrics.onOrderEvent(OrderCompletedEvent.from(order, null));

		assertThat(this.registry.get("orders.tracked").gauge().value()).isZero();
	}

	@Test
	void countsACompletionItNeverSawTheStartOf() {
		Order order = orderIn(OrderStatus.RESERVED);
		order.transitionTo(OrderStatus.PAID);
		this.metrics.onOrderEvent(OrderCompletedEvent.from(order, null));

		assertThat(this.registry.counter("orders.completed", "status", "PAID", "reason", "none").count()).isEqualTo(1);
		assertThat(this.registry.timer("orders.pipeline", "status", "PAID").count()).isZero();
	}

}
