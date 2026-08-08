package com.yz.orderservice.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/**
 * An order placed by a customer.
 *
 * <p>Identity has two layers. {@code id} is the surrogate key exposed by the API,
 * while {@code customerId} + {@code orderReference} form the business key that the
 * database enforces as unique — that constraint is what makes order creation
 * idempotent under request retries and event redelivery.
 */
@Entity
@Table(name = "orders")
public class Order {

	@Id
	@Column(name = "id", nullable = false, updatable = false)
	private UUID id;

	@Column(name = "customer_id", nullable = false, length = 64, updatable = false)
	private String customerId;

	@Column(name = "order_reference", nullable = false, length = 64, updatable = false)
	private String orderReference;

	@Column(name = "product_id", nullable = false, length = 64, updatable = false)
	private String productId;

	@Column(name = "quantity", nullable = false, updatable = false)
	private int quantity;

	@Column(name = "amount", nullable = false, precision = 12, scale = 2, updatable = false)
	private BigDecimal amount;

	@Enumerated(EnumType.STRING)
	@Column(name = "status", nullable = false, length = 16)
	private OrderStatus status;

	@Version
	@Column(name = "version", nullable = false)
	private long version;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	protected Order() {
		// for JPA
	}

	private Order(String customerId, String orderReference, String productId, int quantity, BigDecimal amount) {
		this.id = UUID.randomUUID();
		this.customerId = customerId;
		this.orderReference = orderReference;
		this.productId = productId;
		this.quantity = quantity;
		this.amount = amount;
		this.status = OrderStatus.PENDING;
	}

	/**
	 * Creates a new order in {@link OrderStatus#PENDING}.
	 */
	public static Order create(String customerId, String orderReference, String productId, int quantity,
			BigDecimal amount) {
		Objects.requireNonNull(customerId, "customerId must not be null");
		Objects.requireNonNull(orderReference, "orderReference must not be null");
		Objects.requireNonNull(productId, "productId must not be null");
		Objects.requireNonNull(amount, "amount must not be null");
		if (quantity <= 0) {
			throw new IllegalArgumentException("quantity must be positive, was " + quantity);
		}
		if (amount.signum() < 0) {
			throw new IllegalArgumentException("amount must not be negative, was " + amount);
		}
		return new Order(customerId, orderReference, productId, quantity, amount);
	}

	/**
	 * Moves the order to {@code target}, unless it is already there.
	 *
	 * <p>Re-applying the current status is a no-op rather than an error: consumers
	 * process events at least once, so the same transition will be attempted more
	 * than once and must stay harmless.
	 *
	 * @throws IllegalStateException if the order is in a terminal state
	 */
	public void transitionTo(OrderStatus target) {
		Objects.requireNonNull(target, "target must not be null");
		if (this.status == target) {
			return;
		}
		if (this.status.isTerminal()) {
			throw new IllegalStateException(
					"Order " + this.id + " is in terminal status " + this.status + " and cannot move to " + target);
		}
		this.status = target;
	}

	public UUID getId() {
		return this.id;
	}

	public String getCustomerId() {
		return this.customerId;
	}

	public String getOrderReference() {
		return this.orderReference;
	}

	public String getProductId() {
		return this.productId;
	}

	public int getQuantity() {
		return this.quantity;
	}

	public BigDecimal getAmount() {
		return this.amount;
	}

	public OrderStatus getStatus() {
		return this.status;
	}

	public long getVersion() {
		return this.version;
	}

	public Instant getCreatedAt() {
		return this.createdAt;
	}

	public Instant getUpdatedAt() {
		return this.updatedAt;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Order order)) {
			return false;
		}
		return this.id != null && this.id.equals(order.id);
	}

	@Override
	public int hashCode() {
		return Order.class.hashCode();
	}

	@Override
	public String toString() {
		return "Order{id=%s, customerId='%s', orderReference='%s', status=%s}".formatted(this.id, this.customerId,
				this.orderReference, this.status);
	}

}
