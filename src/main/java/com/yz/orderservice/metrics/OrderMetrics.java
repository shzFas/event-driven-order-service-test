package com.yz.orderservice.metrics;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import com.yz.orderservice.event.OrderCompletedEvent;
import com.yz.orderservice.event.OrderCreatedEvent;
import com.yz.orderservice.event.OrderEvent;
import com.yz.orderservice.event.OrderReservedEvent;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Business metrics for the order pipeline.
 *
 * <p>The HTTP and Kafka meters that Spring Boot ships describe the plumbing: how
 * fast requests are served, how many records a listener consumed. Neither answers
 * the question a load test is actually asking — how long an order takes from
 * accepted to paid, and how many of them end up failing. That end-to-end view spans
 * three consumers and two topics, so no single component can time it.
 *
 * <p>This listener sees every stage of the chain, because the same events that
 * drive the pipeline are published in-process before they go to Kafka. It measures
 * them without any of the services knowing they are being measured.
 */
@Component
public class OrderMetrics {

	/**
	 * Cap on in-flight orders being timed. An order that never reaches a terminal
	 * status — parked in the dead-letter topic, say — leaves its entry behind, so
	 * the map is bounded rather than trusted to drain on its own.
	 */
	private static final int MAX_TRACKED_ORDERS = 100_000;

	private final MeterRegistry registry;

	private final Map<UUID, Timeline> timelines = new ConcurrentHashMap<>();

	private final Counter accepted;

	private final Counter reserved;

	private final Timer reserveStage;

	private final Timer paymentStage;

	public OrderMetrics(MeterRegistry registry) {
		this.registry = registry;
		this.accepted = Counter.builder("orders.accepted")
			// Not "orders.created": Prometheus reserves the _created suffix, and the
			// exporter would strip it back to a bare "orders_total".
			.description("Orders accepted and persisted as PENDING")
			.register(registry);
		this.reserved = Counter.builder("orders.reserved")
			.description("Orders that got their stock reserved")
			.register(registry);
		this.reserveStage = stageTimer("reserve", registry);
		this.paymentStage = stageTimer("payment", registry);
		Gauge.builder("orders.tracked", this.timelines, Map::size)
			.description("Orders currently being timed in memory, capped at " + MAX_TRACKED_ORDERS)
			.register(registry);
	}

	private static Timer stageTimer(String stage, MeterRegistry registry) {
		return Timer.builder("orders.stage")
			.tag("stage", stage)
			.description("Time an order spends in one stage of the pipeline")
			.register(registry);
	}

	/**
	 * Runs after commit, like the Kafka producer does, so a rolled-back transaction
	 * is never counted as work that happened.
	 */
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOrderEvent(OrderEvent event) {
		// Durations come from the monotonic clock rather than the event timestamps:
		// wall-clock time can step backwards and would produce negative samples.
		long now = System.nanoTime();
		if (event instanceof OrderCreatedEvent) {
			this.accepted.increment();
			if (this.timelines.size() < MAX_TRACKED_ORDERS) {
				this.timelines.put(event.orderId(), new Timeline(now, 0));
			}
		}
		else if (event instanceof OrderReservedEvent) {
			this.reserved.increment();
			recordReservation(event.orderId(), now);
		}
		else if (event instanceof OrderCompletedEvent completed) {
			recordCompletion(completed, now);
		}
	}

	private void recordReservation(UUID orderId, long now) {
		Timeline timeline = this.timelines.computeIfPresent(orderId, (id, current) -> current.reservedAt(now));
		if (timeline != null) {
			this.reserveStage.record(now - timeline.createdNanos(), TimeUnit.NANOSECONDS);
		}
	}

	private void recordCompletion(OrderCompletedEvent event, long now) {
		String status = event.status().name();
		this.registry.counter("orders.completed", "status", status, "reason", reasonTag(event.reason())).increment();

		Timeline timeline = this.timelines.remove(event.orderId());
		if (timeline == null) {
			// Created before this process started, or evicted by the cap. The
			// counter above is still accurate; only the duration is unknown.
			return;
		}
		if (timeline.reservedNanos() > 0) {
			this.paymentStage.record(now - timeline.reservedNanos(), TimeUnit.NANOSECONDS);
		}
		this.registry.timer("orders.pipeline", "status", status)
			.record(now - timeline.createdNanos(), TimeUnit.NANOSECONDS);
	}

	/**
	 * Failure reasons are bucketed rather than passed through. The raw text can
	 * carry an order id or a provider message, and a tag value that varies per order
	 * creates a new time series per order.
	 */
	private static String reasonTag(String reason) {
		if (reason == null) {
			return "none";
		}
		return reason.toLowerCase(Locale.ROOT).contains("stock") ? "insufficient_stock" : "payment_declined";
	}

	/**
	 * When an order entered the pipeline and when its stock was reserved.
	 * {@code reservedNanos} is 0 until reservation, and stays 0 for orders that fail
	 * before it.
	 */
	private record Timeline(long createdNanos, long reservedNanos) {

		Timeline reservedAt(long nanos) {
			return new Timeline(this.createdNanos, nanos);
		}

	}

}
