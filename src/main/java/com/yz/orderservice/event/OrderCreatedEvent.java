package com.yz.orderservice.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.yz.orderservice.domain.Order;

/**
 * Published once an order has been persisted in {@code PENDING}.
 *
 * <p>The event carries every field a downstream consumer needs, so consumers never
 * have to call back into this service to enrich what they received.
 */
public record OrderCreatedEvent(UUID orderId, String customerId, String orderReference, String productId, int quantity,
		BigDecimal amount, Instant occurredAt) implements OrderEvent {

	public static OrderCreatedEvent from(Order order) {
		return new OrderCreatedEvent(order.getId(), order.getCustomerId(), order.getOrderReference(),
				order.getProductId(), order.getQuantity(), order.getAmount(), Instant.now());
	}

}
