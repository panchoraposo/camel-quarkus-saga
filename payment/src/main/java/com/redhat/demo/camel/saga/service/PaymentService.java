package com.redhat.demo.camel.saga.service;

import java.time.Instant;

import com.redhat.demo.camel.saga.model.OrderDto;
import com.redhat.demo.entity.Payment;
import com.redhat.demo.repository.PaymentRepository;

import io.quarkus.logging.Log;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;

@ApplicationScoped
@Named("paymentService")
public class PaymentService {

    @Inject
    PaymentRepository paymentRepository;

    public OrderDto createPayment(OrderDto order) {
        String orderId = order.getOrderId();
        // Crear un ID de pago solo con el orderId
        String paymentId = "PAY-" + orderId;
        boolean forceFail = order.getForceFailPayment() != null && order.getForceFailPayment().booleanValue();
        order.setPaymentStatus(forceFail ? "FAILED" : "COMPLETED");
        order.setPaymentId(paymentId);
        order.setDate(Instant.now().toEpochMilli());
        order.setPrice(order.getPrice());
        order.setUserId(order.getUserId());
        order.setOrderId(order.getOrderId());
        order.setSeatId(order.getSeatId());
        order.setOrderStatus(order.getOrderStatus());
        order.setPaymentMessage(forceFail ? "Payment failed (forced for demo)." : "Payment successful");
        
        return order;

    }

    @Transactional
    public OrderDto cancelPayment(OrderDto order) {
        if (order == null) {
            return null;
        }

        String paymentId = order.getPaymentId();
        if (paymentId == null || paymentId.isBlank()) {
            paymentId = "PAY-" + order.getOrderId();
            order.setPaymentId(paymentId);
        }

        Payment payment = paymentRepository.findPaymentById(paymentId);
        if (payment == null) {
            Log.info("Payment does not exist for paymentId=" + paymentId + ". Marking as CANCELLED for saga.");
        } else {
            payment.setStatus("CANCELLED");
        }

        order.setPaymentStatus("CANCELLED");
        order.setPaymentMessage("Payment cancelled due to a downstream/upstream failure.");
        order.setDate(Instant.now().toEpochMilli());
        return order;
    }

    public OrderDto paymentCompensation(OrderDto order) {
        if (order == null) {
            return null;
        }
        order.setPaymentStatus("CANCELLED");
        order.setPaymentMessage("Compensation for payment due to an allocation error.");
        order.setDate(Instant.now().toEpochMilli());
        return order;
    }

}