package com.yz.orderservice.event;

import java.util.Map;

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
 * about a state change that was rolled back. The remaining gap is the reverse case:
 * a crash between commit and send loses the event. Closing that gap properly needs
 * a transactional outbox — the state change and an outbox row are written in one
 * transaction and a relay ships the row afterwards. That is deliberately out of
 * scope here.
 */
@Component
public class OrderEventProducer {

	private static final Logger logger = LoggerFactory.getLogger(OrderEventProducer.class);

	private final KafkaTemplate<String, Object> kafkaTemplate;

	private final Map<Class<? extends OrderEvent>, String> topicsByEventType;

	public OrderEventProducer(KafkaTemplate<String, Object> kafkaTemplate,
			@Value("${app.kafka.topics.orders}") String ordersTopic,
			@Value("${app.kafka.topics.orders-reserved}") String ordersReservedTopic,
			@Value("${app.kafka.topics.orders-completed}") String ordersCompletedTopic) {
		this.kafkaTemplate = kafkaTemplate;
		this.topicsByEventType = Map.of(OrderCreatedEvent.class, ordersTopic, OrderReservedEvent.class,
				ordersReservedTopic, OrderCompletedEvent.class, ordersCompletedTopic);
	}

	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
	public void onOrderEvent(OrderEvent event) {
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
	private void publish(OrderEvent event) {
		String topic = this.topicsByEventType.get(event.getClass());
		if (topic == null) {
			throw new IllegalArgumentException("No topic configured for event type " + event.getClass().getName());
		}

		this.kafkaTemplate.send(topic, event.customerId(), event).whenComplete((result, ex) -> {
			if (ex != null) {
				logger.error("Failed to publish {} for order {}", event.getClass().getSimpleName(), event.orderId(),
						ex);
				return;
			}
			logger.info("Published {} for order {} to {}-{}@{}", event.getClass().getSimpleName(), event.orderId(),
					result.getRecordMetadata().topic(), result.getRecordMetadata().partition(),
					result.getRecordMetadata().offset());
		});
	}

}
