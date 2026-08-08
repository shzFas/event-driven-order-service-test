package com.yz.orderservice.api;

import java.math.BigDecimal;
import java.util.UUID;

import com.yz.orderservice.api.dto.OrderRequest;
import com.yz.orderservice.domain.Order;
import com.yz.orderservice.domain.OrderStatus;
import com.yz.orderservice.service.DuplicateOrderException;
import com.yz.orderservice.service.OrderNotFoundException;
import com.yz.orderservice.service.OrderService;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Covers the HTTP contract on its own — no database, no broker. What matters here
 * is that each failure mode leaves the API with the right status code.
 */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

	private static final String VALID = """
			{"customerId":"cust-1","orderReference":"ref-1","productId":"sku-1","quantity":2,"amount":99.90}""";

	private static final String INVALID = """
			{"customerId":"","orderReference":"ref-1","productId":"sku-1","quantity":0}""";

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private OrderService orderService;

	@Test
	void placingAnOrderIsAcceptedWithItsLocation() throws Exception {
		Order order = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		given(this.orderService.createOrder(any(OrderRequest.class))).willReturn(order);

		this.mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
			.andExpect(status().isAccepted())
			.andExpect(header().string("Location", "/orders/" + order.getId()))
			.andExpect(jsonPath("$.id").value(order.getId().toString()))
			.andExpect(jsonPath("$.status").value(OrderStatus.PENDING.name()));
	}

	@Test
	void malformedPayloadIsRejectedFieldByField() throws Exception {
		this.mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(INVALID))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.title").value("Validation failed"))
			.andExpect(jsonPath("$.errors.customerId").exists())
			.andExpect(jsonPath("$.errors.quantity").exists())
			.andExpect(jsonPath("$.errors.amount").exists());
	}

	@Test
	void duplicateBusinessKeyIsAConflict() throws Exception {
		willThrow(new DuplicateOrderException("cust-1", "ref-1")).given(this.orderService)
			.createOrder(any(OrderRequest.class));

		this.mockMvc.perform(post("/orders").contentType(MediaType.APPLICATION_JSON).content(VALID))
			.andExpect(status().isConflict())
			.andExpect(jsonPath("$.title").value("Duplicate order"))
			.andExpect(jsonPath("$.customerId").value("cust-1"))
			.andExpect(jsonPath("$.orderReference").value("ref-1"));
	}

	@Test
	void fetchingAnOrderReturnsIt() throws Exception {
		Order order = Order.create("cust-1", "ref-1", "sku-1", 2, new BigDecimal("99.90"));
		given(this.orderService.getOrder(order.getId())).willReturn(order);

		this.mockMvc.perform(get("/orders/{id}", order.getId()))
			.andExpect(status().isOk())
			.andExpect(jsonPath("$.customerId").value("cust-1"))
			.andExpect(jsonPath("$.amount").value(99.90));
	}

	@Test
	void fetchingAnUnknownOrderIsNotFound() throws Exception {
		UUID missing = UUID.randomUUID();
		willThrow(new OrderNotFoundException(missing)).given(this.orderService).getOrder(missing);

		this.mockMvc.perform(get("/orders/{id}", missing))
			.andExpect(status().isNotFound())
			.andExpect(jsonPath("$.title").value("Order not found"));
	}

	@Test
	void identifierThatIsNotAUuidIsARequestError() throws Exception {
		this.mockMvc.perform(get("/orders/{id}", "not-a-uuid"))
			.andExpect(status().isBadRequest())
			.andExpect(jsonPath("$.title").value("Invalid request"));
	}

}
