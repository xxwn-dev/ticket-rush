package com.xxwn.ticket_rush.domain.payment;

public record PaymentConfirmRequest(String paymentKey, String orderId, int amount) {}
