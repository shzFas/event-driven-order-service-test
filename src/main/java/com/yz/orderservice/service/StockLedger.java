package com.yz.orderservice.service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Stand-in for a real inventory system.
 *
 * <p>Stock is held in memory, which is fine for a simulation but means it resets on
 * restart. What matters for this project is that reserving is a real side effect
 * with an observable count — that is what makes duplicate event delivery visible in
 * tests: processing the same event twice would decrement twice.
 */
@Component
public class StockLedger {

	private static final Logger logger = LoggerFactory.getLogger(StockLedger.class);

	private final Map<String, AtomicInteger> stockByProduct = new ConcurrentHashMap<>();

	private final int unitsPerProduct;

	private final List<String> unavailableProducts;

	public StockLedger(@Value("${app.stock.units-per-product}") int unitsPerProduct,
			@Value("${app.stock.unavailable-products}") List<String> unavailableProducts) {
		this.unitsPerProduct = unitsPerProduct;
		this.unavailableProducts = unavailableProducts;
	}

	/**
	 * Reserves {@code quantity} units, if there are enough.
	 *
	 * @return true when the reservation succeeded
	 */
	public boolean reserve(String productId, int quantity) {
		if (this.unavailableProducts.contains(productId)) {
			logger.info("Product {} is flagged unavailable, refusing reservation", productId);
			return false;
		}

		AtomicInteger available = this.stockByProduct.computeIfAbsent(productId,
				(key) -> new AtomicInteger(this.unitsPerProduct));

		// Compare-and-set rather than decrement-then-check: two concurrent
		// reservations must never both succeed on the last remaining unit.
		while (true) {
			int current = available.get();
			if (current < quantity) {
				logger.info("Insufficient stock for {}: {} requested, {} available", productId, quantity, current);
				return false;
			}
			if (available.compareAndSet(current, current - quantity)) {
				logger.debug("Reserved {} units of {}, {} left", quantity, productId, current - quantity);
				return true;
			}
		}
	}

	public int available(String productId) {
		AtomicInteger available = this.stockByProduct.get(productId);
		return (available != null) ? available.get() : this.unitsPerProduct;
	}

	public void reset() {
		this.stockByProduct.clear();
	}

}
