package com.yz.orderservice.service;

public record PaymentResult(boolean successful, String transactionId, String reason) {

	public static PaymentResult success(String transactionId) {
		return new PaymentResult(true, transactionId, null);
	}

	public static PaymentResult declined(String reason) {
		return new PaymentResult(false, null, reason);
	}

}
