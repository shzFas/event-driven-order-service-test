package com.yz.orderservice.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Publishes order events to Kafka.
 *
 * <p>Sending happens after the producing transaction commits, so Kafka never learns
 * about an order that was rolled back. The remaining gap is the reverse case: a
 * crash between commit and send loses the event. Closing that gap properly needs a
 * transactional outbox — the order and the outbox row are written in one
 * transaction and a relay ships the row afterwards. That is deliberately out of
 * scope here.
 */
@Component
public class OrderEventProducer {

	private static final Logger logger = LoggerFactory.getLogger(OrderEventProducer.class);

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final String ordersTopic;

	public OrderEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
			@Value("${app.kafka.topics.orders}") String ordersTopic) {
		this.kafkaTemplate = kafkaTemplate;
		this.ordersTopic = ordersTopic;
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOrderCreated(OrderCreatedEvent event) {
		publish(event);
	}

	/**
	 * Sends the event keyed by customer id.
	 *
	 * <p>The key decides the partition, and Kafka preserves order within a
	 * partition. Keying by customer therefore keeps one customer's events in
	 * sequence while still letting different customers be processed in parallel
	 * across partitions — ordering where it matters, throughput everywhere else.
	 */
	public void publish(OrderCreatedEvent event) {
		this.kafkaTemplate.send(this.ordersTopic, event.customerId(), event).whenComplete((result, ex) -> {
			if (ex != null) {
				logger.error("Failed to publish OrderCreatedEvent for order {}", event.orderId(), ex);
				return;
			}
			logger.info("Published OrderCreatedEvent for order {} to {}-{}@{}", event.orderId(),
					result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
					result.getRecordMetadata().offset());
		});
	}

}
