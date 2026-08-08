package com.yz.orderservice.api;

import java.util.LinkedHashMap;
import java.util.Map;

import com.yz.orderservice.service.DuplicateOrderException;
import com.yz.orderservice.service.OrderNotFoundException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns exceptions into RFC 9457 problem responses, so clients get one predictable
 * error shape instead of whatever each layer happened to throw.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final Logger logger = LoggerFactory.getLogger(ApiExceptionHandler.class);

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
		Map<String, String> errors = new LinkedHashMap<>();
		ex.getBindingResult()
			.getFieldErrors()
			.forEach((error) -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));

		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("Validation failed");
		problem.setDetail("One or more fields are invalid");
		problem.setProperty("errors", errors);
		return problem;
	}

	/**
	 * A path variable or query parameter that cannot be converted — an order id that
	 * is not a UUID, for instance. The caller sent something wrong, so this is a 400
	 * rather than the 500 it would otherwise fall through to.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	ProblemDetail handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("Invalid request");
		problem.setDetail("Parameter '%s' has an unexpected value: %s".formatted(ex.getName(), ex.getValue()));
		return problem;
	}

	/**
	 * Business invariants rejected by {@code Order.create(...)}. A payload can be
	 * well formed and still describe an impossible order.
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	ProblemDetail handleInvalidOrder(IllegalArgumentException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
		problem.setTitle("Invalid order");
		problem.setDetail(ex.getMessage());
		return problem;
	}

	@ExceptionHandler(OrderNotFoundException.class)
	ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.NOT_FOUND);
		problem.setTitle("Order not found");
		problem.setDetail(ex.getMessage());
		return problem;
	}

	@ExceptionHandler(DuplicateOrderException.class)
	ProblemDetail handleDuplicateOrder(DuplicateOrderException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
		problem.setTitle("Duplicate order");
		problem.setDetail(ex.getMessage());
		problem.setProperty("customerId", ex.getCustomerId());
		problem.setProperty("orderReference", ex.getOrderReference());
		return problem;
	}

	@ExceptionHandler(IllegalStateException.class)
	ProblemDetail handleIllegalState(IllegalStateException ex) {
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.CONFLICT);
		problem.setTitle("Illegal state transition");
		problem.setDetail(ex.getMessage());
		return problem;
	}

	@ExceptionHandler(Exception.class)
	ProblemDetail handleUnexpected(Exception ex) {
		// Log the cause, but never leak internals such as stack traces or SQL to the caller.
		logger.error("Unhandled exception", ex);
		ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
		problem.setTitle("Internal server error");
		problem.setDetail("The request could not be processed");
		return problem;
	}

}
