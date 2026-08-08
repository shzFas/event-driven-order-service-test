package com.yz.orderservice.domain;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatNoException;

class OrderTest {

	private static Order pendingOrder() {
		return Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
	}

	@Test
	void newOrderStartsPendingWithAnIdentity() {
		Order order = pendingOrder();

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(order.getId()).isNotNull();
		assertThat(order.getCustomerId()).isEqualTo("cust-1");
		assertThat(order.getAmount()).isEqualByComparingTo("99.90");
	}

	@Test
	void quantityMustBePositive() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> Order.create("cust-1", "ref-1", "sku-1", 0, BigDecimal.ONE))
			.withMessageContaining("quantity must be positive");
	}

	@Test
	void amountMustNotBeNegative() {
		assertThatExceptionOfType(IllegalArgumentException.class)
			.isThrownBy(() -> Order.create("cust-1", "ref-1", "sku-1", 1, new BigDecimal("-0.01")))
			.withMessageContaining("amount must not be negative");
	}

	@Test
	void requiredFieldsAreRejectedWhenNull() {
		assertThatExceptionOfType(NullPointerException.class)
			.isThrownBy(() -> Order.create(null, "ref-1", "sku-1", 1, BigDecimal.ONE));
		assertThatExceptionOfType(NullPointerException.class)
			.isThrownBy(() -> Order.create("cust-1", null, "sku-1", 1, BigDecimal.ONE));
		assertThatExceptionOfType(NullPointerException.class)
			.isThrownBy(() -> Order.create("cust-1", "ref-1", null, 1, BigDecimal.ONE));
		assertThatExceptionOfType(NullPointerException.class)
			.isThrownBy(() -> Order.create("cust-1", "ref-1", "sku-1", 1, null));
	}

	@Test
	void orderMovesForwardThroughItsLifecycle() {
		Order order = pendingOrder();

		order.transitionTo(OrderStatus.RESERVED);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);

		order.transitionTo(OrderStatus.PAID);
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	/**
	 * The property the whole event flow leans on: consumers are at-least-once, so
	 * re-applying a transition that already happened must be harmless.
	 */
	@Test
	void repeatingTheCurrentStatusIsANoOp() {
		Order order = pendingOrder();
		order.transitionTo(OrderStatus.RESERVED);

		assertThatNoException().isThrownBy(() -> order.transitionTo(OrderStatus.RESERVED));
		assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
	}

	@Test
	void repeatingATerminalStatusIsStillANoOp() {
		Order order = pendingOrder();
		order.transitionTo(OrderStatus.RESERVED);
		order.transitionTo(OrderStatus.PAID);

		assertThatNoException().isThrownBy(() -> order.transitionTo(OrderStatus.PAID));
		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
	}

	@Test
	void terminalOrderCannotMoveOn() {
		Order paid = pendingOrder();
		paid.transitionTo(OrderStatus.RESERVED);
		paid.transitionTo(OrderStatus.PAID);

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> paid.transitionTo(OrderStatus.FAILED))
			.withMessageContaining("terminal status PAID");

		Order failed = pendingOrder();
		failed.transitionTo(OrderStatus.FAILED);

		assertThatExceptionOfType(IllegalStateException.class)
			.isThrownBy(() -> failed.transitionTo(OrderStatus.PAID));
	}

	@Test
	void terminalStatusesAreMarkedAsSuch() {
		assertThat(OrderStatus.PENDING.isTerminal()).isFalse();
		assertThat(OrderStatus.RESERVED.isTerminal()).isFalse();
		assertThat(OrderStatus.PAID.isTerminal()).isTrue();
		assertThat(OrderStatus.FAILED.isTerminal()).isTrue();
	}

	@Test
	void ordersAreDistinguishedByIdentity() {
		Order order = pendingOrder();
		Order other = pendingOrder();

		assertThat(order).isEqualTo(order).isNotEqualTo(other).isNotEqualTo(null);
	}

}
