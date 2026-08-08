package com.yz.orderservice.service;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCompletedEvent;
import com.yz.orderservice.event.OrderCreatedEvent;
import com.yz.orderservice.event.OrderReservedEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockService {

	private static final Logger logger = LoggerFactory.getLogger(StockService.class);

	private final OrderRepository orderRepository;

	private final StockLedger stockLedger;

	private final ApplicationEventPublisher eventPublisher;

	public StockService(OrderRepository orderRepository, StockLedger stockLedger,
			ApplicationEventPublisher eventPublisher) {
		this.orderRepository = orderRepository;
		this.stockLedger = stockLedger;
		this.eventPublisher = eventPublisher;
	}

	/**
	 * Reserves stock for a newly created order.
	 *
	 * <p>Idempotent by design, because Kafka delivers at least once. The status
	 * check is the guard that matters: reserving stock is a side effect with a
	 * counter behind it, so a redelivered event must not reach the ledger a second
	 * time. An order that is no longer PENDING has already been handled, and the
	 * call becomes a no-op.
	 */
	@Transactional
	public void reserveStock(OrderCreatedEvent event) {
		Order order = this.orderRepository.findById(event.orderId()).orElse(null);
		if (order == null) {
			// Nothing to act on. Retrying will not conjure the order into existence,
			// so this is logged and dropped rather than retried into the DLT.
			logger.warn("Ignoring OrderCreatedEvent for unknown order {}", event.orderId());
			return;
		}

		if (order.getStatus() != OrderStatus.PENDING) {
			logger.info("Order {} is already {}, skipping duplicate reservation", order.getId(), order.getStatus());
			return;
		}

		if (!this.stockLedger.reserve(order.getProductId(), order.getQuantity())) {
			order.transitionTo(OrderStatus.FAILED);
			this.eventPublisher.publishEvent(OrderCompletedEvent.from(order, "Insufficient stock"));
			logger.info("Order {} failed: insufficient stock for {}", order.getId(), order.getProductId());
			return;
		}

		order.transitionTo(OrderStatus.RESERVED);
		this.eventPublisher.publishEvent(OrderReservedEvent.from(order));
		logger.info("Reserved stock for order {}", order.getId());
	}

}
