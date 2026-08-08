package com.yz.orderservice.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCompletedEvent;
import com.yz.orderservice.event.OrderReservedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private PaymentClient paymentClient;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private PaymentService paymentService;

	@BeforeEach
	void setUp() {
		this.paymentService = new PaymentService(this.orderRepository, this.paymentClient, this.eventPublisher);
	}

	private static Order reservedOrder() {
		Order order = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		order.transitionTo(OrderStatus.RESERVED);
		return order;
	}

	private static OrderReservedEvent eventFor(Order order) {
		return OrderReservedEvent.from(order);
	}

	@Test
	void successfulChargeMarksTheOrderPaid() {
		Order order = reservedOrder();
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));
		given(this.paymentClient.charge(eq(order.getId()), any(BigDecimal.class)))
			.willReturn(PaymentResult.success("txn-1"));

		this.paymentService.processPayment(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(this.eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue()).isInstanceOfSatisfying(OrderCompletedEvent.class, (event) -> {
			assertThat(event.status()).isEqualTo(OrderStatus.PAID);
			assertThat(event.reason()).isNull();
		});
	}

	/**
	 * A declined result is what the circuit breaker's fallback returns when the
	 * provider is down. The order must land in FAILED rather than sit in RESERVED
	 * forever waiting for a payment that will never be attempted again.
	 */
	@Test
	void declinedChargeMarksTheOrderFailedWithAReason() {
		Order order = reservedOrder();
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));
		given(this.paymentClient.charge(eq(order.getId()), any(BigDecimal.class)))
			.willReturn(PaymentResult.declined("Payment provider is unavailable"));

		this.paymentService.processPayment(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.FAILED);
		ArgumentCaptor<Object> captor = ArgumentCaptor.forClass(Object.class);
		verify(this.eventPublisher).publishEvent(captor.capture());
		assertThat(captor.getValue()).isInstanceOfSatisfying(OrderCompletedEvent.class, (event) -> {
			assertThat(event.status()).isEqualTo(OrderStatus.FAILED);
			assertThat(event.reason()).isEqualTo("Payment provider is unavailable");
		});
	}

	/**
	 * Guards against the expensive duplicate: charging a customer twice for one
	 * order because Kafka delivered the event again.
	 */
	@Test
	void alreadyPaidOrderIsNotChargedAgain() {
		Order order = reservedOrder();
		order.transitionTo(OrderStatus.PAID);
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));

		this.paymentService.processPayment(eventFor(order));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
		verifyNoInteractions(this.paymentClient);
		verifyNoInteractions(this.eventPublisher);
	}

	@Test
	void orderStillPendingIsNotCharged() {
		Order order = Order.create("cust-1", "ref-1", "sku-1", 1, BigDecimal.ONE);
		given(this.orderRepository.findById(order.getId())).willReturn(Optional.of(order));

		this.paymentService.processPayment(new OrderReservedEvent(order.getId(), order.getCustomerId(),
				order.getProductId(), order.getQuantity(), order.getAmount(), Instant.now()));

		assertThat(order.getStatus()).isEqualTo(OrderStatus.PENDING);
		verifyNoInteractions(this.paymentClient);
	}

	@Test
	void eventForAnUnknownOrderIsDropped() {
		OrderReservedEvent orphan = new OrderReservedEvent(UUID.randomUUID(), "cust-1", "sku-1", 1, BigDecimal.ONE,
				Instant.now());
		given(this.orderRepository.findById(orphan.orderId())).willReturn(Optional.empty());

		this.paymentService.processPayment(orphan);

		verifyNoInteractions(this.paymentClient);
		verifyNoInteractions(this.eventPublisher);
	}

}
