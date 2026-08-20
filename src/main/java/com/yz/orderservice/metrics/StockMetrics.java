package com.yz.orderservice.metrics;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.yz.orderservice.event.OrderCreatedEvent;
import com.yz.orderservice.service.StockLedger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes the remaining stock of every product the service has seen.
 *
 * <p>Worth watching during a load run: the ledger starts with a fixed number of
 * units per product, and once a product is drained every further order for it fails
 * on reservation. Without this gauge that shows up only as a wave of FAILED orders
 * with no visible cause.
 *
 * <p>Gauges are registered as products appear rather than up front, because the
 * ledger has no catalogue — it learns about a product the first time one is
 * ordered.
 */
@Component
public class StockMetrics {

	/**
	 * A tag value that varies without bound is the classic way to melt a Prometheus
	 * server. A load generator that invents a fresh SKU per order stops being
	 * tracked here rather than taking the metrics endpoint down with it.
	 */
	private static final int MAX_TRACKED_PRODUCTS = 200;

	private static final Logger logger = LoggerFactory.getLogger(StockMetrics.class);

	private final MeterRegistry registry;

	private final StockLedger stockLedger;

	private final Set<String> trackedProducts = ConcurrentHashMap.newKeySet();

	public StockMetrics(MeterRegistry registry, StockLedger stockLedger) {
		this.registry = registry;
		this.stockLedger = stockLedger;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOrderCreated(OrderCreatedEvent event) {
		String productId = event.productId();
		if (this.trackedProducts.contains(productId)) {
			return;
		}
		if (this.trackedProducts.size() >= MAX_TRACKED_PRODUCTS) {
			logger.debug("Not tracking stock for {}: already at {} products", productId, MAX_TRACKED_PRODUCTS);
			return;
		}
		if (this.trackedProducts.add(productId)) {
			// The gauge reads the ledger on every scrape, so it stays current
			// without anything having to push updates into it.
			Gauge.builder("stock.available", this.stockLedger, (ledger) -> ledger.available(productId))
				.tag("product", productId)
				.baseUnit("units")
				.description("Units left in the stock ledger")
				.register(this.registry);
		}
	}

}
