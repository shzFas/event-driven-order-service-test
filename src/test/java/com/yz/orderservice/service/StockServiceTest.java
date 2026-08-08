package com.yz.orderservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCompletedEvent;
import com.yz.orderservice.event.OrderCreatedEvent;
import com.yz.orderservice.event.OrderReservedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class StockServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private StockLedger stockLedger;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private StockService stockService;

	@BeforeEach
	void setUp() {
		this.stockService = new StockService(this.orderRepository, this.stockLedger, this.eventPublisher);
	}

	private static Order orderIn(OrderStatus status) {
		Order order = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		if (status != OrderStatus.PENDING) {
			order.transitionTo(status);
		}
		return order;
	}

	private static OrderCreatedEvent eventFor(Order order) {
		return OrderCreatedEvent.from(order);
	}

	@Test
	void reservationMovesOrderToReservedAndAnnouncesIt() {
		Order order = orderIn(OrderStatus.PENDING);
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));
		given(this.stockLedger.reserve("sku-1", 2)).willReturn(true);

		this.stockService.reserveStock(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(this.eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue()).isInstanceOf(OrderReservedEvent.class);
	}

	@Test
	void missingStockFailsTheOrder() {
		Order order = orderIn(OrderStatus.PENDING);
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));
		given(this.stockLedger.reserve("sku-1", 2)).willReturn(false);

		this.stockService.reserveStock(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(this.eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue()).isInstanceOfSatisfying(OrderCompletedEvent.class,
				(event) -> assertThat(event.reason()).isEqualTo("Insufficient stock"));
	}

	/**
	 * The redelivery case. An order past PENDING has been reserved already, so the
	 * ledger must not be touched again — that is what stops stock leaking away one
	 * duplicate at a time.
	 */
	@Test
	void redeliveredEventDoesNotTouchTheLedgerAgain() {
		Order order = orderIn(OrderStatus.RESERVED);
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));

		this.stockService.reserveStock(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.RESERVED);
		verifyNoInteractions(this.stockLedger);
		verifyNoInteractions(this.eventPublisher);
	}

	@Test
	void eventForACompletedOrderIsIgnored() {
		Order order = orderIn(OrderStatus.RESERVED);
		order.transitionTo(OrderStatus.PAID);
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));

		this.stockService.reserveStock(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		verifyNoInteractions(this.stockLedger);
	}

	/**
	 * An event whose order is absent cannot succeed on retry either, so it is
	 * dropped quietly instead of being thrown into the retry-and-dead-letter path.
	 */
	@Test
	void eventForAnUnknownOrderIsDroppedRatherThanRetried() {
		OrderCreatedEvent orphan = new OrderCreatedEvent(UUID.randomUUID(), "cust-1", "ref-1", "sku-1", 1,
				BigDecimal.ONE, Instant.now());
		given(this.orderRepository.findById(orphan.orderId())).willReturn(Optional.empty());

		this.stockService.reserveStock(orphan);

		verify(this.stockLedger, never()).reserve(anyString(), anyInt());
		verifyNoInteractions(this.eventPublisher);
	}

}
