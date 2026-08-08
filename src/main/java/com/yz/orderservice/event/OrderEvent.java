package com.yz.orderservice.event;

import java.util.UUID;

/**
 * Common shape of every order event.
 *
 * <p>{@code customerId} is the Kafka partition key for all of them, which keeps one
 * customer's events on a single partition and therefore in order, at every stage of
 * the chain.
 */
public interface OrderEvent {

	UUID orderId();

	String customerId();

}
