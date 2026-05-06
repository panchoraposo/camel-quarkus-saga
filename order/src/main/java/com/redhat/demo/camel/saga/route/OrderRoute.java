package com.redhat.demo.camel.saga.route;

import java.util.UUID;

import org.apache.camel.LoggingLevel;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.Exchange;
import org.apache.camel.model.dataformat.JsonLibrary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.redhat.demo.camel.saga.avro.OrderEvent;
import com.redhat.demo.camel.saga.event.OrderEventMapper;
import com.redhat.demo.camel.saga.model.OrderDto;
import com.redhat.demo.camel.saga.observability.TbTelemetry;
import com.redhat.demo.camel.saga.repository.OrderRepository;
import com.redhat.demo.camel.saga.service.OrderService;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class OrderRoute extends RouteBuilder {

        @Inject
        OrderService orderService;

        @Inject
        OrderRepository orderRepository;

        @Inject
        MeterRegistry meterRegistry;

        @Override
        public void configure() throws Exception {

                onCompletion().process(TbTelemetry::endSpanAndClearMdc);

                onException(Exception.class)
                        .handled(true)
                        .log(LoggingLevel.ERROR, "Order request failed: ${exception.message}")
                        .process(exchange -> {
                                OrderDto order = exchange.getMessage().getBody(OrderDto.class);
                                if (order == null) {
                                        order = new OrderDto();
                                }
                                if (order.getOrderId() == null) {
                                        order.setOrderId(exchange.getMessage().getHeader("id", String.class));
                                }
                                if (order.getSagaId() == null) {
                                        order.setSagaId(order.getOrderId());
                                }
                                order.setOrderStatus("FAILED");
                                if (order.getOrderMessage() == null || order.getOrderMessage().isBlank()) {
                                        order.setOrderMessage("Order processing failed.");
                                }
                                exchange.getMessage().setHeader(Exchange.HTTP_RESPONSE_CODE, 200);
                                exchange.getMessage().setBody(order);
                        });

                // Endpoint REST para crear un pedido
                from("rest:post:/order")
                        .process(TbTelemetry::putMdcFromCurrentSpan)
                        .unmarshal().json(JsonLibrary.Jackson, OrderDto.class)
                        .process(exchange -> {
                                exchange.getMessage().setHeader("id", UUID.randomUUID().toString());
                                OrderDto order = exchange.getMessage().getBody(OrderDto.class);
                                order.setOrderId(exchange.getMessage().getHeader("id", String.class));
                                order.setSagaId(order.getOrderId());
                                exchange.getMessage().setBody(order);

                                ObjectMapper objectMapper = new ObjectMapper();
                                objectMapper.configure(SerializationFeature.FAIL_ON_EMPTY_BEANS, false);
                                String jsonOrder = objectMapper.writeValueAsString(order);
                                exchange.getMessage().setHeader("orderJson", jsonOrder);
                        })
                        .log(LoggingLevel.INFO, "Order received: ${header.orderJson}")
                        .to("direct:newOrder")
                        .process(exchange -> {
                                OrderDto order = exchange.getMessage().getBody(OrderDto.class);
                                String status = order != null && order.getOrderStatus() != null ? order.getOrderStatus() : "UNKNOWN";
                                meterRegistry.counter("ticketblaster_saga_orders_total", "status", status).increment();
                        })
                        .marshal().json(JsonLibrary.Jackson);

                from("direct:newOrder")
                        .log("Processing order id: ${header.id}")
                        .setBody(body())
                        .bean(orderService, "enrichUserFromBearer")
                        .bean(orderService, "createOrder")
                        .bean(orderService, "reserveBudget")
                        .log("Persisting order in database: ${header.id}")
                        // Database
                        .to("direct:insertOrder")
                        .choice()
                                .when().simple("${body.orderStatus} == 'FAILED'")
                                        .log("Order failed before starting distributed saga: ${body.orderMessage}")
                                .otherwise()
                                        .process(exchange -> {
                                                OrderDto order = exchange.getIn().getBody(OrderDto.class);
                                                // Keep a structured response body for the REST caller.
                                                exchange.setProperty("orderResponse", order);

                                                if (order != null) {
                                                        order.setEventType("OrderCreated");
                                                        order.setSagaId(order.getOrderId());
                                                }
                                                exchange.getIn().setBody(OrderEventMapper.toEvent(order));
                                        })
                                        .setHeader(KafkaConstants.KEY, simple("${header.id}"))
                                        .process(TbTelemetry::injectTraceContextIntoHeaders)
                                        .log("Sending OrderCreated event to Kafka: ${body}")
                                        .to("kafka:order-events")
                                        .log("OrderCreated sent to Kafka topic: order-events.")
                                        .process(exchange -> {
                                                OrderDto order = exchange.getProperty("orderResponse", OrderDto.class);
                                                if (order != null) {
                                                        // Avoid leaking internal event metadata to REST clients.
                                                        order.setEventType(null);
                                                }
                                                exchange.getIn().setBody(order);
                                        })
                        .end();
                
                from("direct:insertOrder")
                        .process(exchange -> {
                                OrderDto order = exchange.getMessage().getBody(OrderDto.class);
                                // setHeader(null) would remove headers; keep explicit keys (null values allowed)
                                exchange.getMessage().getHeaders().put("orderId", order != null ? order.getOrderId() : null);
                                exchange.getMessage().getHeaders().put("seatId", order != null ? order.getSeatId() : null);
                                exchange.getMessage().getHeaders().put("orderStatus", order != null ? order.getOrderStatus() : null);
                                exchange.getMessage().getHeaders().put("userId", order != null ? order.getUserId() : null);
                                exchange.getMessage().getHeaders().put("orderMessage", order != null ? order.getOrderMessage() : null);
                        })
                        .log("Headers: orderId=${header.orderId}, userId=${header.userId}, seatId=${header.seatId}, orderStatus=${header.orderStatus}, orderMessage=${header.orderMessage}")
                        .log("SQL: {{sql.insertOrder}}")
                        .to("sql:{{sql.insertOrder}}")
                        .log("Order inserted successfully");

                // Allocation result (seat-events)
                from("kafka:seat-events")
                        .process(exchange -> TbTelemetry.startKafkaConsumerSpan(exchange, "kafka.consume seat-events"))
                        .process(exchange -> exchange.getMessage().setBody(
                                OrderEventMapper.toDto(exchange.getMessage().getBody(OrderEvent.class))))
                        .filter().simple("${body.eventType} == 'SeatReserveFailed'")
                        .process(exchange -> meterRegistry.counter("ticketblaster_kafka_events_consumed_total", "topic", "seat-events", "eventType", "SeatReserveFailed").increment())
                        .log("Seat reservation failed: ${body.seatMessage}")
                        .setHeader("orderId", simple("${body.orderId}"))
                        .setHeader("orderStatus", constant("FAILED"))
                        .setHeader("orderMessage", constant("Order failed due to seat allocation."))
                        .setHeader("seatStatus", simple("${body.seatStatus}"))
                        .setHeader("seatMessage", simple("${body.seatMessage}"))
                        .setHeader("paymentId", simple("${body.paymentId}"))
                        .setHeader("paymentStatus", simple("${body.paymentStatus}"))
                        .setHeader("paymentMessage", simple("${body.paymentMessage}"))
                        .setHeader("date", simple("${body.date}"))
                        .setHeader("price", simple("${body.price}"))
                        .bean(orderService, "refundBudget")
                        .to("direct:updateOrder");

                // Seat release due to compensation (allocate publishes SeatReleased after CompensateSeat)
                from("kafka:seat-events")
                        .process(exchange -> TbTelemetry.startKafkaConsumerSpan(exchange, "kafka.consume seat-events"))
                        .process(exchange -> exchange.getMessage().setBody(
                                OrderEventMapper.toDto(exchange.getMessage().getBody(OrderEvent.class))))
                        .filter().simple("${body.eventType} == 'SeatReleased'")
                        .process(exchange -> meterRegistry.counter("ticketblaster_kafka_events_consumed_total", "topic", "seat-events", "eventType", "SeatReleased").increment())
                        .log("Seat released after compensation: ${body.seatMessage}")
                        .setHeader("orderId", simple("${body.orderId}"))
                        .setHeader("seatStatus", simple("${body.seatStatus}"))
                        .setHeader("seatMessage", simple("${body.seatMessage}"))
                        .log("SQL: {{sql.updateOrderSeat}}")
                        .to("sql:{{sql.updateOrderSeat}}");

                from("kafka:payment-events")
                        .process(exchange -> TbTelemetry.startKafkaConsumerSpan(exchange, "kafka.consume payment-events"))
                        .log("Raw Payment event from Kafka: ${body}")
                        .process(exchange -> exchange.getMessage().setBody(
                                OrderEventMapper.toDto(exchange.getMessage().getBody(OrderEvent.class))))
                        .log("Payment eventType=${body.eventType} paymentStatus=${body.paymentStatus}")
                        .setHeader("orderId", simple("${body.orderId}"))
                        .choice()
                                .when().simple("${body.eventType} == 'PaymentFailed'")
                                        .setHeader("orderStatus", constant("FAILED"))
                                        .setHeader("orderMessage", constant("Order failed due to payment failure."))
                                .when().simple("${body.eventType} == 'PaymentCompleted'")
                                        .setHeader("orderStatus", constant("COMPLETED"))
                                        .setHeader("orderMessage", constant("Order completed."))
                        .end()
                        .setHeader("paymentId", simple("${body.paymentId}"))
                        .setHeader("paymentStatus", simple("${body.paymentStatus}"))
                        .setHeader("paymentMessage", simple("${body.paymentMessage}"))
                        .setHeader("seatStatus", simple("${body.seatStatus}"))
                        .setHeader("seatMessage", simple("${body.seatMessage}"))
                        .setHeader("date", simple("${body.date}"))
                        .setHeader("price", simple("${body.price}"))
                        .choice()
                                .when().simple("${header.orderStatus} == 'FAILED'")
                                        .bean(orderService, "refundBudget")
                                        .process(exchange -> {
                                                OrderDto order = exchange.getIn().getBody(OrderDto.class);
                                                if (order != null) {
                                                        order.setEventType("CompensateSeat");
                                                        order.setSagaId(order.getOrderId());
                                                }
                                        })
                                        .setProperty("orderObj", body())
                                        .process(exchange -> exchange.getIn().setBody(
                                                OrderEventMapper.toEvent(exchange.getIn().getBody(OrderDto.class))))
                                        .setHeader(KafkaConstants.KEY, simple("${header.orderId}"))
                                        .process(TbTelemetry::injectTraceContextIntoHeaders)
                                        .to("kafka:compensation-events")
                                        .setBody(exchangeProperty("orderObj"))
                        .end()
                        .to("direct:updateOrder");

                from("direct:updateOrder")
                        .log("Headers antes de actualizar en SQL: orderId=${header.orderId}, paymentId=${header.paymentId}, paymentStatus=${header.paymentStatus}, paymentMessage=${header.paymentMessage}, seatStatus=${header.seatStatus}, seatMessage=${header.seatMessage}, orderStatus=${header.orderStatus}, orderMessage=${header.orderMessage}")
                        .bean(orderService, "updateOrder")
                        .log("SQL: {{sql.updateOrder}}")
                        .to("sql:{{sql.updateOrder}}")
                        .log("Order: ${header.orderId} updated.");

        }

}