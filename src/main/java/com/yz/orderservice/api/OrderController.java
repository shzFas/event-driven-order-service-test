package com.yz.orderservice.api;

import java.net.URI;
import java.util.UUID;

import com.yz.orderservice.api.dto.OrderRequest;
import com.yz.orderservice.api.dto.OrderResponse;
import com.yz.orderservice.domain.Order;
import com.yz.orderservice.service.OrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
@Tag(name = "Orders", description = "Placing and retrieving orders")
public class OrderController {

	private final OrderService orderService;

	public OrderController(OrderService orderService) {
		this.orderService = orderService;
	}

	@PostMapping
	@Operation(summary = "Place an order",
			description = """
					Stores the order as PENDING and publishes an OrderCreatedEvent. \
					Responds 202 rather than 201 because stock reservation and payment \
					happen asynchronously afterwards — the order exists, but it is not \
					finished. Poll GET /orders/{id} to follow its progress.""")
	@ApiResponses({ @ApiResponse(responseCode = "202", description = "Order accepted for processing"),
			@ApiResponse(responseCode = "400", description = "Payload failed validation", content = @Content),
			@ApiResponse(responseCode = "409", description = "Order reference already used by this customer",
					content = @Content) })
	public ResponseEntity<OrderResponse> createOrder(@Valid @RequestBody OrderRequest request) {
		Order order = this.orderService.createOrder(request);
		return ResponseEntity.accepted()
			.location(URI.create("/orders/" + order.getId()))
			.body(OrderResponse.from(order));
	}

	@GetMapping("/{id}")
	@Operation(summary = "Fetch an order by id")
	@ApiResponses({ @ApiResponse(responseCode = "200", description = "Order found"),
			@ApiResponse(responseCode = "404", description = "No such order", content = @Content) })
	public OrderResponse getOrder(@PathVariable UUID id) {
		return OrderResponse.from(this.orderService.getOrder(id));
	}

}
