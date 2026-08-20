package com.yz.orderservice.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * How many orders sit in each status right now, counted in the database.
 *
 * <p>Counters tell you what the pipeline did; this tells you what it is holding.
 * Under load, a growing PENDING or RESERVED population is the earliest sign that
 * consumers are falling behind producers — visible before consumer lag translates
 * into anything a customer would notice.
 */
@Component
public class OrderStatusMetrics {

	private static final Logger logger = LoggerFactory.getLogger(OrderStatusMetrics.class);

	private final OrderRepository orderRepository;

	private final Map<OrderStatus, Long> lastKnownCounts = new ConcurrentHashMap<>();

	public OrderStatusMetrics(MeterRegistry registry, OrderRepository orderRepository) {
		this.orderRepository = orderRepository;
		for (OrderStatus status : OrderStatus.values()) {
			Gauge.builder("orders.current", () -> count(status))
				.tag("status", status.name())
				.description("Orders currently in this status")
				.register(registry);
		}
	}

	/**
	 * Queried on scrape, which is cheap enough here — {@code idx_orders_status}
	 * covers it, and Prometheus asks every few seconds.
	 */
	private double count(OrderStatus status) {
		try {
			long count = this.orderRepository.countByStatus(status);
			this.lastKnownCounts.put(status, count);
			return count;
		}
		catch (DataAccessException ex) {
			// A scrape must not fail because the database is briefly unreachable;
			// reporting the last known value keeps the rest of the page intact.
			logger.debug("Could not count {} orders, reporting the last known value", status, ex);
			return this.lastKnownCounts.getOrDefault(status, 0L);
		}
	}

}
