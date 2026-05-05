package com.redhat.demo.camel.saga.route;

import java.util.List;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.model.dataformat.JsonLibrary;

import com.redhat.demo.camel.saga.model.OrderDto;
import com.redhat.demo.camel.saga.observability.TbTelemetry;
import com.redhat.demo.camel.saga.service.AllocateService;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class AllocateRoute extends RouteBuilder {

        @Inject
        AllocateService allocateService;

        @Inject
        MeterRegistry meterRegistry;

        @Override
        public void configure() throws Exception {

                onCompletion().process(TbTelemetry::endSpanAndClearMdc);

                onException(Exception.class)
                        .log("Exception occurred: ${exception.message}")
                        .useOriginalMessage()
                        .handled(true)
                        .to("direct:seatReserveFailed");

                // Consumir eventos de ordenes
                from("kafka:order-events")
                        .process(exchange -> TbTelemetry.startKafkaConsumerSpan(exchange, "kafka.consume order-events"))
                        .unmarshal().json(JsonLibrary.Jackson, OrderDto.class)
                        .filter().simple("${body.eventType} == 'OrderCreated'")
                        .process(exchange -> meterRegistry.counter("ticketblaster_kafka_events_consumed_total", "topic", "order-events", "eventType", "OrderCreated").increment())
                        .setHeader("seatId", simple("${body.seatId}"))
                        // Preserve important fields on the Exchange itself.
                        // Kafka headers can arrive as byte[] and cause type conversion issues in failure paths.
                        .setProperty("tb.userId", simple("${body.userId}"))
                        .setProperty("tb.price", simple("${body.price}"))
                        .setProperty("tb.budgetReserved", simple("${body.budgetReserved}"))
                        .setProperty("tb.forceFailPayment", simple("${body.forceFailPayment}"))
                        .log("DEBUG - Received Kafka message: ${body}")
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
                        .process(TbTelemetry::injectTraceContextIntoHeaders)
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
                                // Preserve correlation and identifiers even when the original message is missing fields
                                if (order.getSeatId() == null) {
                                        order.setSeatId(exchange.getIn().getHeader("seatId", String.class));
                                }
                                if (order.getOrderId() == null) {
                                        order.setOrderId(exchange.getIn().getHeader("orderId", String.class));
                                }
                                if (order.getUserId() == null) {
                                        order.setUserId(exchange.getProperty("tb.userId", Long.class));
                                }
                                if (order.getPrice() == null) {
                                        order.setPrice(exchange.getProperty("tb.price", Double.class));
                                }
                                if (order.getBudgetReserved() == null) {
                                        order.setBudgetReserved(exchange.getProperty("tb.budgetReserved", Boolean.class));
                                }
                                if (order.getForceFailPayment() == null) {
                                        order.setForceFailPayment(exchange.getProperty("tb.forceFailPayment", Boolean.class));
                                }
                                if (order.getSagaId() == null) {
                                        order.setSagaId(order.getOrderId());
                                }
                                order.setEventType("SeatReserveFailed");
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
                        .process(TbTelemetry::injectTraceContextIntoHeaders)
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
                        .process(exchange -> TbTelemetry.startKafkaConsumerSpan(exchange, "kafka.consume compensation-events"))
                        .unmarshal().json(JsonLibrary.Jackson, OrderDto.class)
                        .filter().simple("${body.eventType} == 'CompensateSeat'")
                        .process(exchange -> meterRegistry.counter("ticketblaster_kafka_events_consumed_total", "topic", "compensation-events", "eventType", "CompensateSeat").increment())
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
                        .process(TbTelemetry::injectTraceContextIntoHeaders)
                        .log("Publishing SeatReleased to seat-events: ${body}")
                        .to("kafka:seat-events");

        }
}