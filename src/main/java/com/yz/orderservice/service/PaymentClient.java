package com.yz.orderservice.service;

import java.math.BigDecimal;
import java.util.UUID;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Guards calls to {@link PaymentGateway} with a circuit breaker.
 *
 * <p>Without it, an outage at the provider would be absorbed one slow, failing call
 * at a time: every message in the partition waits for its own timeout before
 * failing, the consumer falls behind, and retries pile more load onto a system that
 * is already struggling. Once the breaker opens, calls fail immediately through the
 * fallback — the provider gets room to recover and this service stays responsive.
 */
@Component
public class PaymentClient {

	private static final Logger logger = LoggerFactory.getLogger(PaymentClient.class);

	private final PaymentGateway paymentGateway;

	public PaymentClient(PaymentGateway paymentGateway) {
		this.paymentGateway = paymentGateway;
	}

	@CircuitBreaker(name = "payment", fallbackMethod = "chargeFallback")
	public PaymentResult charge(UUID orderId, BigDecimal amount) {
		return PaymentResult.success(this.paymentGateway.charge(orderId, amount));
	}

	/**
	 * Invoked both when a call fails and when the breaker is open and rejects it
	 * outright. Either way the order is declined rather than left in limbo: a
	 * definite FAILED is easier to reason about — and to compensate — than an order
	 * stuck in RESERVED forever.
	 */
	@SuppressWarnings("unused")
	private PaymentResult chargeFallback(UUID orderId, BigDecimal amount, Throwable cause) {
		logger.warn("Payment fallback for order {}: {}", orderId, cause.toString());
		return PaymentResult.declined(cause.getMessage());
	}

}
