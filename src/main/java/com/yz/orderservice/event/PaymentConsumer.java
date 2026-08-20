package com.yz.orderservice.event;

import com.yz.orderservice.service.PaymentService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Charges orders whose stock has been reserved.
 */
@Component
public class PaymentConsumer {

	private static final Logger logger = LoggerFactory.getLogger(PaymentConsumer.class);

	private final PaymentService paymentService;

	public PaymentConsumer(PaymentService paymentService) {
		this.paymentService = paymentService;
	}

	@KafkaListener(id = "payment-service", topics = "${app.kafka.topics.orders-reserved}",
			groupId = "payment-service")
	public void onOrderReserved(OrderReservedEvent event, Acknowledgment acknowledgment) {
		logger.debug("Received OrderReservedEvent for order {}", event.orderId());
		this.paymentService.processPayment(event);
		acknowledgment.acknowledge();
	}

}
