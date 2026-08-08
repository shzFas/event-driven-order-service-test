package com.yz.orderservice.domain;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, UUID> {

	/**
	 * Looks up an order by its business key. Used to turn a duplicate request or a
	 * redelivered event into a no-op instead of a second order.
	 */
	Optional<Order> findByCustomerIdAndOrderReference(String customerId, String orderReference);

}
