package com.redhat.demo.camel.saga.event;

import com.redhat.demo.camel.saga.avro.OrderEvent;
import com.redhat.demo.camel.saga.model.OrderDto;

public final class OrderEventMapper {

    private OrderEventMapper() {
    }

    public static OrderEvent toEvent(OrderDto dto) {
        OrderEvent e = new OrderEvent();
        if (dto == null) {
            e.setEventType("Unknown");
            e.setOrderId("unknown");
            return e;
        }
        e.setEventType(dto.getEventType() != null ? dto.getEventType() : "Unknown");
        e.setSagaId(dto.getSagaId());
        e.setOrderId(dto.getOrderId() != null ? dto.getOrderId() : "unknown");

        e.setUserId(dto.getUserId());
        e.setSeatId(dto.getSeatId());
        e.setPrice(dto.getPrice());
        e.setDate(dto.getDate());

        e.setOrderStatus(dto.getOrderStatus());
        e.setOrderMessage(dto.getOrderMessage());

        e.setSeatStatus(dto.getSeatStatus());
        e.setSeatMessage(dto.getSeatMessage());

        e.setPaymentId(dto.getPaymentId());
        e.setPaymentStatus(dto.getPaymentStatus());
        e.setPaymentMessage(dto.getPaymentMessage());

        e.setBudgetReserved(dto.getBudgetReserved());
        e.setForceFailPayment(dto.getForceFailPayment());
        return e;
    }

    public static OrderDto toDto(OrderEvent e) {
        OrderDto dto = new OrderDto();
        if (e == null) {
            dto.setEventType("Unknown");
            dto.setOrderId("unknown");
            return dto;
        }
        dto.setEventType(e.getEventType() != null ? e.getEventType().toString() : null);
        dto.setSagaId(e.getSagaId() != null ? e.getSagaId().toString() : null);
        dto.setOrderId(e.getOrderId() != null ? e.getOrderId().toString() : null);

        dto.setUserId(e.getUserId());
        dto.setSeatId(e.getSeatId() != null ? e.getSeatId().toString() : null);
        dto.setPrice(e.getPrice());
        dto.setDate(e.getDate());

        dto.setOrderStatus(e.getOrderStatus() != null ? e.getOrderStatus().toString() : null);
        dto.setOrderMessage(e.getOrderMessage() != null ? e.getOrderMessage().toString() : null);

        dto.setSeatStatus(e.getSeatStatus() != null ? e.getSeatStatus().toString() : null);
        dto.setSeatMessage(e.getSeatMessage() != null ? e.getSeatMessage().toString() : null);

        dto.setPaymentId(e.getPaymentId() != null ? e.getPaymentId().toString() : null);
        dto.setPaymentStatus(e.getPaymentStatus() != null ? e.getPaymentStatus().toString() : null);
        dto.setPaymentMessage(e.getPaymentMessage() != null ? e.getPaymentMessage().toString() : null);

        dto.setBudgetReserved(e.getBudgetReserved());
        dto.setForceFailPayment(e.getForceFailPayment());
        return dto;
    }
}
