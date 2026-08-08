package com.yz.orderservice.service;

/**
 * Raised when an order already exists for the given business key.
 */
public class DuplicateOrderException extends RuntimeException {

	private final String customerId;

	private final String orderReference;

	public DuplicateOrderException(String customerId, String orderReference) {
		super("Order reference '" + orderReference + "' already exists for customer '" + customerId + "'");
		this.customerId = customerId;
		this.orderReference = orderReference;
	}

	public String getCustomerId() {
		return this.customerId;
	}

	public String getOrderReference() {
		return this.orderReference;
	}

}
