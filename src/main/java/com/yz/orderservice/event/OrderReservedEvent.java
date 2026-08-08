package com.yz.orderservice.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.yz.orderservice.domain.Order;

/**
 * Published once stock has been reserved. Triggers payment.
 */
public record OrderReservedEvent(UUID orderId, String customerId, String productId, int quantity, BigDecimal amount,
		Instant occurredAt) implements OrderEvent {

	public static OrderReservedEvent from(Order order) {
		return new OrderReservedEvent(order.getId(), order.getCustomerId(), order.getProductId(), order.getQuantity(),
				order.getAmount(), Instant.now());
	}

}
