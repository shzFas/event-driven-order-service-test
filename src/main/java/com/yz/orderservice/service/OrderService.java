package com.yz.orderservice.service;

import java.util.UUID;

import com.yz.orderservice.api.dto.OrderRequest;
import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.event.OrderCreatedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

	private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

	private final OrderRepository orderRepository;

	private final ApplicationEventPublisher eventPublisher;

	public OrderService(OrderRepository orderRepository, ApplicationEventPublisher eventPublisher) {
		this.orderRepository = orderRepository;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Persists a new order in {@code PENDING} and announces it.
	 *
	 * <p>The event is handed to the application context rather than to Kafka
	 * directly: {@code OrderEventProducer} picks it up only after this transaction
	 * commits. That ordering means a published event always refers to an order that
	 * really is in the database.
	 *
	 * @throws DuplicateOrderException if the business key is already taken
	 */
	@Transactional
	public Order createOrder(OrderRequest request) {
		this.orderRepository.findByCustomerIdAndOrderReference(request.customerId(), request.orderReference())
			.ifPresent((existing) -> {
				throw new DuplicateOrderException(request.customerId(), request.orderReference());
			});

		Order order = Order.create(request.customerId(), request.orderReference(), request.productId(),
				request.quantity(), request.amount());

		Order saved;
		try {
			// Flush inside the try block so that a losing race on the business key
			// surfaces here as a duplicate, rather than as an opaque failure at commit.
			saved = this.orderRepository.saveAndFlush(order);
		}
		catch (DataIntegrityViolationException ex) {
			logger.debug("Concurrent create lost the race for business key {}/{}", request.customerId(),
					request.orderReference());
			throw new DuplicateOrderException(request.customerId(), request.orderReference());
		}

		this.eventPublisher.publishEvent(OrderCreatedEvent.from(saved));
		logger.info("Created order {} for customer {}", saved.getId(), saved.getCustomerId());
		return saved;
	}

	@Transactional(readOnly = true)
	public Order getOrder(UUID orderId) {
		return this.orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
	}

}
