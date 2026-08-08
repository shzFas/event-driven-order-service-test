package com.yz.orderservice.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Incoming order.
 *
 * <p>These constraints cover shape only — that the payload is well formed. Business
 * invariants are enforced separately by {@code Order.create(...)}, so they hold no
 * matter which code path builds an order.
 */
@Schema(description = "Order to be placed")
public record OrderRequest(

		@Schema(description = "Identifier of the customer placing the order", example = "cust-1")
		@NotBlank @Size(max = 64) String customerId,

		@Schema(description = "Customer-supplied reference. Unique per customer, which makes retries safe",
				example = "ref-1")
		@NotBlank @Size(max = 64) String orderReference,

		@Schema(description = "Identifier of the ordered product", example = "sku-1")
		@NotBlank @Size(max = 64) String productId,

		@Schema(description = "Number of units ordered", example = "2")
		@NotNull @Positive Integer quantity,

		@Schema(description = "Total order amount", example = "99.90")
		@NotNull @PositiveOrZero @Digits(integer = 10, fraction = 2) BigDecimal amount) {
}
