package com.yz.orderservice.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in for an external payment provider.
 *
 * <p>Deliberately unreliable: it fails a configurable share of calls and can be
 * made to fail every call by setting {@code app.payment.failure-rate} to 1.0. That
 * switch is what lets the circuit breaker be demonstrated against a running system
 * instead of only in tests.
 */
@Component
public class PaymentGateway {

	private static final Logger logger = LoggerFactory.getLogger(PaymentGateway.class);

	private final double failureRate;

	private final long latencyMillis;

	public PaymentGateway(@Value("${app.payment.failure-rate}") double failureRate,
			@Value("${app.payment.latency-millis}") long latencyMillis) {
		this.failureRate = failureRate;
		this.latencyMillis = latencyMillis;
	}

	/**
	 * @throws PaymentGatewayException when the simulated provider is unavailable
	 */
	public String charge(UUID orderId, BigDecimal amount) {
		if (this.latencyMillis > 0) {
			sleep();
		}
		if (ThreadLocalRandom.current().nextDouble() < this.failureRate) {
			logger.warn("Payment provider refused the call for order {}", orderId);
			throw new PaymentGatewayException("Payment provider is unavailable");
		}
		String transactionId = UUID.randomUUID().toString();
		logger.info("Charged {} for order {}, transaction {}", amount, orderId, transactionId);
		return transactionId;
	}

	private void sleep() {
		try {
			Thread.sleep(this.latencyMillis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new PaymentGatewayException("Interrupted while calling payment provider");
		}
	}

}
