package com.yz.orderservice.event;

import com.yz.orderservice.domain.OrderStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Terminal step of the chain: would notify the customer that their order is done.
 *
 * <p>Logs instead of sending anything. It earns its place by showing that a new
 * consumer can be added to the flow without touching a single existing component —
 * which is the point of publishing events rather than calling services directly.
 */
@Component
public class NotificationConsumer {

	private static final Logger logger = LoggerFactory.getLogger(NotificationConsumer.class);

	@KafkaListener(topics = "${app.kafka.topics.orders-completed}", groupId = "notification-service")
	public void onOrderCompleted(OrderCompletedEvent event, Acknowledgment acknowledgment) {
		if (event.status() == OrderStatus.PAID) {
			logger.info("Notifying customer {}: order {} is confirmed", event.customerId(), event.orderId());
		}
		else {
			logger.info("Notifying customer {}: order {} could not be completed ({})", event.customerId(),
					event.orderId(), event.reason());
		}
		acknowledgment.acknowledge();
	}

}
