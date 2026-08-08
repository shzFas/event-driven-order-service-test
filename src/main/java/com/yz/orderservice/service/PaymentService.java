package com.yz.orderservice.service;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCompletedEvent;
import com.yz.orderservice.event.OrderReservedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PaymentService {

	private static final Logger logger = LoggerFactory.getLogger(PaymentService.class);

	private final OrderRepository orderRepository;

	private final PaymentClient paymentClient;

	private final ApplicationEventPublisher eventPublisher;

	public PaymentService(OrderRepository orderRepository, PaymentClient paymentClient,
			ApplicationEventPublisher eventPublisher) {
		this.orderRepository = orderRepository;
		this.paymentClient = paymentClient;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Charges a reserved order.
	 *
	 * <p>Same guard as stock reservation, and here it protects something more
	 * expensive than a counter: charging a customer twice for one order. Only an
	 * order still in RESERVED is eligible, so a redelivered event finds it already
	 * PAID or FAILED and does nothing.
	 */
	@Transactional
	public void processPayment(OrderReservedEvent event) {
		Order order = this.orderRepository.findById(event.orderId()).orElse(null);
		if (order == null) {
			logger.warn("Ignoring OrderReservedEvent for unknown order {}", event.orderId());
			return;
		}

		if (order.getStatus() != OrderStatus.RESERVED) {
			logger.info("Order {} is already {}, skipping duplicate payment", order.getId(), order.getStatus());
			return;
		}

		PaymentResult result = this.paymentClient.charge(order.getId(), order.getAmount());
		if (result.successful()) {
			order.transitionTo(OrderStatus.PAID);
			this.eventPublisher.publishEvent(OrderCompletedEvent.from(order, null));
			logger.info("Order {} paid, transaction {}", order.getId(), result.transactionId());
			return;
		}

		order.transitionTo(OrderStatus.FAILED);
		this.eventPublisher.publishEvent(OrderCompletedEvent.from(order, result.reason()));
		logger.info("Order {} failed at payment: {}", order.getId(), result.reason());
	}

}
