package com.yz.orderservice.event;

import com.yz.orderservice.service.StockService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Reserves stock for newly created orders.
 *
 * <p>The listener stays a thin adapter and delegates to {@code StockService}. That
 * is not only tidiness: the transaction has to commit before the offset is
 * committed, and calling a {@code @Transactional} method on this same bean would
 * bypass the proxy and silently run without a transaction.
 */
@Component
public class StockConsumer {

	private static final Logger logger = LoggerFactory.getLogger(StockConsumer.class);

	private final StockService stockService;

	public StockConsumer(StockService stockService) {
		this.stockService = stockService;
	}

	@KafkaListener(topics = "${app.kafka.topics.orders}", groupId = "stock-service")
	public void onOrderCreated(OrderCreatedEvent event, Acknowledgment acknowledgment) {
		logger.debug("Received OrderCreatedEvent for order {}", event.orderId());
		this.stockService.reserveStock(event);

		// Acknowledged only once processing succeeded and its transaction committed.
		// If this thread dies earlier, the offset stays put and the event is
		// redelivered — at-least-once, which the idempotency guards absorb.
		acknowledgment.acknowledge();
	}

}
