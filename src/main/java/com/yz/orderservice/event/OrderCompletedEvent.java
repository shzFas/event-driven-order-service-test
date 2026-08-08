package com.yz.orderservice.event;

import java.time.Instant;
import java.util.UUID;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderStatus;

/**
 * Published when an order reaches a terminal status, whether that is PAID or
 * FAILED. {@code reason} explains a failure and is null on success.
 */
public record OrderCompletedEvent(UUID orderId, String customerId, OrderStatus status, String reason,
		Instant occurredAt) implements OrderEvent {

	public static OrderCompletedEvent from(Order order, String reason) {
		return new OrderCompletedEvent(order.getId(), order.getCustomerId(), order.getStatus(), reason, Instant.now());
	}

}
