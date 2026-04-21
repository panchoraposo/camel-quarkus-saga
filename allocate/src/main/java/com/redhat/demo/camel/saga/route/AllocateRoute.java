package com.redhat.demo.camel.saga.route;

import java.util.List;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;

import com.redhat.demo.camel.saga.model.OrderDto;
import com.redhat.demo.camel.saga.service.AllocateService;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AllocateRoute extends RouteBuilder {

        @Inject
        AllocateService allocateService;

        @Override
        public void configure() throws Exception {

                onException(Exception.class)
                        .log("Exception occurred: ${exception.message}")
                        .handled(true)
                        .to("direct:seatReserveFailed");

                // Consumir eventos de ordenes
                from("kafka:order-events")
                        .unmarshal().json(JsonLibrary.Jackson, OrderDto.class)
                        .filter().simple("${body.eventType} == 'OrderCreated'")
                        .setHeader("seatId", simple("${body.seatId}"))
                        .log("DEBUG - Received Kafka message: ${body}")
                        .setBody(simple("${body}"))
                        .setHeader("seatId", simple("${body.seatId}"))
                        .setHeader("orderId", simple("${body.orderId}"))
                        .to("direct:allocateSeat");
                
                // Asignar asiento, y si falla, realizar compensación
                from("direct:allocateSeat")
                        .log("Allocating seat for: ${body}")
                        .bean(allocateService, "allocateSeat")
                        .process(exchange -> {
                                OrderDto order = exchange.getIn().getBody(OrderDto.class);
                                if (order != null) {
                                        order.setEventType("SeatReserved");
                                        if (order.getSagaId() == null) {
                                                order.setSagaId(order.getOrderId());
                                        }
                                }
                        })
                        // Actualizar el estado del asiento en base de datos
                        .log("Updating seat in database: ${body}")
                        .to("direct:updateSeat")
                        // Producir evento a Kafka
                        .marshal().json(JsonLibrary.Jackson)
                        .setHeader(KafkaConstants.KEY, simple("${header.orderId}"))
                        .log("Sending allocation event to Kafka: ${body}")
                        .to("kafka:seat-events")
                        .log("Seat allocation sent to Kafka topic: seat-events");

                // Si la reserva falla, emitimos resultado en seat-events (no compensation command)
                from("direct:seatReserveFailed")
                        .process(exchange -> {
                                OrderDto order = exchange.getIn().getBody(OrderDto.class);
                                if (order == null) {
                                        order = new OrderDto();
                                }
                                order.setEventType("SeatReserveFailed");
                                if (order.getSagaId() == null) {
                                        order.setSagaId(order.getOrderId());
                                }
                                if (order.getSeatStatus() == null) {
                                        order.setSeatStatus("FAILED");
                                }
                                if (order.getSeatMessage() == null) {
                                        order.setSeatMessage("Seat reservation failed.");
                                }
                                exchange.getIn().setBody(order);
                        })
                        .marshal().json(JsonLibrary.Jackson)
                        .setHeader(KafkaConstants.KEY, simple("${header.orderId}"))
                        .to("kafka:seat-events");
                
                // Update seat
                from("direct:updateSeat")
                        .setHeader("seatId", simple("${header.seatId}"))
                        .setHeader("newStatus", simple("${body.seatStatus}"))
                        .setHeader("orderId", simple("${body.orderId}"))
                        .setProperty("orderBeforeSql", body())
                        .choice()
                                .when().simple("${body.seatStatus} == 'FREE'")
                                        .log("SQL: {{sql.releaseSeat}}")
                                        .to("sql:{{sql.releaseSeat}}")
                                .otherwise()
                                        .log("SQL: {{sql.reserveSeat}}")
                                        .to("sql:{{sql.reserveSeat}}")
                                        .process(exchange -> {
                                                Object raw = exchange.getMessage().getHeader("CamelSqlUpdateCount");
                                                int updated = 0;
                                                if (raw instanceof Number) {
                                                        updated = ((Number) raw).intValue();
                                                } else if (raw instanceof List) {
                                                        // Some drivers return a list with one number
                                                        List<?> l = (List<?>) raw;
                                                        if (!l.isEmpty() && l.get(0) instanceof Number) {
                                                                updated = ((Number) l.get(0)).intValue();
                                                        }
                                                }
                                                if (updated == 0) {
                                                        throw new RuntimeException("Seat " + exchange.getIn().getHeader("seatId") + " is already allocated.");
                                                }
                                        })
                        .end()
                        .setBody(exchangeProperty("orderBeforeSql"))
                        .log("Seat updated successfully");

                // Compensation triggered by downstream services (e.g. payment failed) -> release seat
                from("kafka:compensation-events")
                        .unmarshal().json(JsonLibrary.Jackson, OrderDto.class)
                        .filter().simple("${body.eventType} == 'CompensateSeat'")
                        .log("Downstream compensation received, releasing seat. Event: ${body}")
                        .setHeader("seatId", simple("${body.seatId}"))
                        .setHeader("orderId", simple("${body.orderId}"))
                        .bean(allocateService, "releaseSeat")
                        .process(exchange -> {
                                OrderDto order = exchange.getIn().getBody(OrderDto.class);
                                if (order != null) {
                                        order.setEventType("SeatReleased");
                                        if (order.getSagaId() == null) {
                                                order.setSagaId(order.getOrderId());
                                        }
                                }
                        })
                        .to("direct:updateSeat")
                        .marshal().json(JsonLibrary.Jackson)
                        .setHeader(KafkaConstants.KEY, simple("${header.orderId}"))
                        .log("Publishing SeatReleased to seat-events: ${body}")
                        .to("kafka:seat-events");

        }
}