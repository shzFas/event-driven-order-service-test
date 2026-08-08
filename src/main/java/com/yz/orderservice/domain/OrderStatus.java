package com.yz.orderservice.domain;

/**
 * Lifecycle of an order.
 *
 * <p>{@link #PENDING} is the only state an order is created in. {@link #PAID} and
 * {@link #FAILED} are terminal.
 */
public enum OrderStatus {

	/** Accepted and persisted, no downstream processing has happened yet. */
	PENDING,

	/** Stock has been reserved for the order. */
	RESERVED,

	/** Payment succeeded. Terminal. */
	PAID,

	/** Stock reservation or payment failed. Terminal. */
	FAILED;

	public boolean isTerminal() {
		return this == PAID || this == FAILED;
	}

}
