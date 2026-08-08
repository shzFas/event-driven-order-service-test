package com.yz.orderservice.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderStatus;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Order as currently stored")
public record OrderResponse(UUID id, String customerId, String orderReference, String productId, int quantity,
		BigDecimal amount, OrderStatus status, Instant createdAt, Instant updatedAt) {

	public static OrderResponse from(Order order) {
		return new OrderResponse(order.getId(), order.getCustomerId(), order.getOrderReference(), order.getProductId(),
				order.getQuantity(), order.getAmount(), order.getStatus(), order.getCreatedAt(), order.getUpdatedAt());
	}

}
