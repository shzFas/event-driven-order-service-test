package com.yz.orderservice.service;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import com.yz.orderservice.api.dto.OrderRequest;
import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderRepository;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.event.OrderCreatedEvent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

	private static final OrderRequest REQUEST = new OrderRequest("cust-1", "ref-1", "sku-1", 2,
			new BigDecimal("99.90"));

	@Mock
	private OrderRepository orderRepository;

	@Mock
	private ApplicationEventPublisher eventPublisher;

	private OrderService orderService;

	@BeforeEach
	void setUp() {
		this.orderService = new OrderService(this.orderRepository, this.eventPublisher);
	}

	@Test
	void createOrderPersistsOrderAsPending() {
		given(this.orderRepository.findByCustomerIdAndOrderReference("cust-1", "ref-1")).willReturn(Optional.empty());
		given(this.orderRepository.saveAndFlush(any(Order.class))).willAnswer((invocation) -> invocation.getArgument(0));

		Order created = this.orderService.createOrder(REQUEST);

		assertThat(created.getStatus()).isEqualTo(OrderStatus.PENDING);
		assertThat(created.getCustomerId()).isEqualTo("cust-1");
		assertThat(created.getOrderReference()).isEqualTo("ref-1");
		assertThat(created.getProductId()).isEqualTo("sku-1");
		assertThat(created.getQuantity()).isEqualTo(2);
		assertThat(created.getAmount()).isEqualByComparingTo("99.90");
		assertThat(created.getId()).isNotNull();
	}

	@Test
	void createOrderPublishesEventDescribingTheSavedOrder() {
		given(this.orderRepository.findByCustomerIdAndOrderReference("cust-1", "ref-1")).willReturn(Optional.empty());
		given(this.orderRepository.saveAndFlush(any(Order.class))).willAnswer((invocation) -> invocation.getArgument(0));

		Order created = this.orderService.createOrder(REQUEST);

		ArgumentCaptor<OrderCreatedEvent> captor = ArgumentCaptor.forClass(OrderCreatedEvent.class);
		verify(this.eventPublisher).publishEvent(captor.capture());

		OrderCreatedEvent event = captor.getValue();
		assertThat(event.orderId()).isEqualTo(created.getId());
		assertThat(event.customerId()).isEqualTo("cust-1");
		assertThat(event.orderReference()).isEqualTo("ref-1");
		assertThat(event.productId()).isEqualTo("sku-1");
		assertThat(event.quantity()).isEqualTo(2);
		assertThat(event.amount()).isEqualByComparingTo("99.90");
		assertThat(event.occurredAt()).isNotNull();
	}

	@Test
	void createOrderRejectsKnownBusinessKeyWithoutSavingOrPublishing() {
		Order existing = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		given(this.orderRepository.findByCustomerIdAndOrderReference("cust-1", "ref-1"))
			.willReturn(Optional.of(existing));

		assertThatExceptionOfType(DuplicateOrderException.class)
			.isThrownBy(() -> this.orderService.createOrder(REQUEST))
			.satisfies((ex) -> {
				assertThat(ex.getCustomerId()).isEqualTo("cust-1");
				assertThat(ex.getOrderReference()).isEqualTo("ref-1");
			});

		verify(this.orderRepository, never()).saveAndFlush(any(Order.class));
		verifyNoInteractions(this.eventPublisher);
	}

	/**
	 * Two concurrent requests can both pass the lookup. The loser is rejected by the
	 * unique constraint, and that must surface as the same duplicate error rather
	 * than a 500.
	 */
	@Test
	void createOrderTranslatesConstraintViolationIntoDuplicate() {
		given(this.orderRepository.findByCustomerIdAndOrderReference("cust-1", "ref-1")).willReturn(Optional.empty());
		given(this.orderRepository.saveAndFlush(any(Order.class)))
			.willThrow(new DataIntegrityViolationException("uk_orders_customer_id_order_reference"));

		assertThatExceptionOfType(DuplicateOrderException.class)
			.isThrownBy(() -> this.orderService.createOrder(REQUEST));

		verifyNoInteractions(this.eventPublisher);
	}

	@Test
	void getOrderReturnsStoredOrder() {
		Order stored = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		given(this.orderRepository.findById(stored.getId())).willReturn(Optional.of(stored));

		assertThat(this.orderService.getOrder(stored.getId())).isSameAs(stored);
	}

	@Test
	void getOrderFailsWhenMissing() {
		UUID missing = UUID.randomUUID();
		given(this.orderRepository.findById(missing)).willReturn(Optional.empty());

		assertThatExceptionOfType(OrderNotFoundException.class)
			.isThrownBy(() -> this.orderService.getOrder(missing));
	}

}
