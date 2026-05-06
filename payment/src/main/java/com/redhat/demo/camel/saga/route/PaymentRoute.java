package com.redhat.demo.camel.saga.route;

import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.kafka.KafkaConstants;

import com.redhat.demo.camel.saga.avro.OrderEvent;
import com.redhat.demo.camel.saga.event.OrderEventMapper;
import com.redhat.demo.camel.saga.model.OrderDto;
import com.redhat.demo.camel.saga.observability.TbTelemetry;
import com.redhat.demo.camel.saga.service.PaymentService;
import com.redhat.demo.repository.PaymentRepository;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class PaymentRoute extends RouteBuilder {

        @Inject
        PaymentService paymentService;

        @Inject
        PaymentRepository paymentRepository;

        @Inject
        MeterRegistry meterRegistry;

        @Override
        public void configure() throws Exception {

                onCompletion().process(TbTelemetry::endSpanAndClearMdc);

                onException(Exception.class)
                        .log("Exception occurred: ${exception.message}")
                        .useOriginalMessage()
                        .handled(true)
                        .process(exchange -> {
                                Object body = exchange.getIn().getBody();
                                OrderDto order = body instanceof OrderDto ? (OrderDto) body : null;
                                if (order == null) {
                                        order = new OrderDto();
                                }
                                order.setEventType("PaymentFailed");
                                order.setPaymentStatus("FAILED");
                                order.setPaymentMessage("Payment failed: " + exchange.getProperty("CamelExceptionCaught"));
                                if (order.getSagaId() == null) {
                                        order.setSagaId(order.getOrderId());
                                }
                                exchange.getIn().setBody(order);
                        })
                        .process(exchange -> exchange.getIn().setBody(
                                OrderEventMapper.toEvent(exchange.getIn().getBody(OrderDto.class))))
                        .setHeader(KafkaConstants.KEY, simple("${body.orderId}"))
                        .process(TbTelemetry::injectTraceContextIntoHeaders)
                        .to("kafka:payment-events");

                // Escuchar eventos de pedidos
                from("kafka:seat-events")
                        .process(exchange -> TbTelemetry.startKafkaConsumerSpan(exchange, "kafka.consume seat-events"))
                        .log("DEBUG - Received Kafka message: ${body}")
                        .process(exchange -> exchange.getMessage().setBody(
                                OrderEventMapper.toDto(exchange.getMessage().getBody(OrderEvent.class))))
                        .setHeader("price", simple("${body.price}"))
                        .setHeader("orderId", simple("${body.orderId}"))
                        .setHeader("seatId", simple("${body.seatId}"))
                        .filter().simple("${body.eventType} == 'SeatReserved'")
                        .process(exchange -> meterRegistry.counter("ticketblaster_kafka_events_consumed_total", "topic", "seat-events", "eventType", "SeatReserved").increment())
                        .to("direct:processPayment");

                // Process payment
                from("direct:processPayment")
                        .bean(paymentService, "createPayment")
                        .setHeader("paymentId", simple("${body.paymentId}"))
                        .log("Header payment id: ${header.paymentId}")
                        .setBody(simple("${body}"))
                        .log("Persisting payment in database: ${body}")
                        // Database insert
                        .to("direct:insertPayment")
                        .process(exchange -> {
                                OrderDto order = exchange.getIn().getBody(OrderDto.class);
                                if (order != null) {
                                        String status = order.getPaymentStatus();
                                        order.setEventType("COMPLETED".equalsIgnoreCase(status) ? "PaymentCompleted" : "PaymentFailed");
                                        if (order.getSagaId() == null) {
                                                order.setSagaId(order.getOrderId());
                                        }
                                }
                        })
                        .process(exchange -> exchange.getIn().setBody(
                                OrderEventMapper.toEvent(exchange.getIn().getBody(OrderDto.class))))
                        .setHeader(KafkaConstants.KEY, simple("${header.orderId}"))
                        .process(TbTelemetry::injectTraceContextIntoHeaders)
                        .to("kafka:payment-events") // Publicar evento de pago (éxito o fallo)
                        .log("Sending payment event to Kafka: ${body}")
                        .log("Payment sent to Kafka topic: payment-events");                        

                from("direct:insertPayment")
                        .setHeader("paymentId", simple("${header.paymentId}"))
                        .setHeader("price", simple("${header.price}"))
                        //.setHeader("status", simple("${body.paymentStatus}"))
                        .setHeader("date", simple("${body.date}"))
                        .setHeader("status", simple("${body.paymentStatus}"))
                        .setHeader("orderMessage", simple("${body.paymentMessage}"))
                        .log("Headers antes de insertar en SQL: paymentId=${header.paymentId}, price=${header.price}, status=${header.status}, date=${header.date}")
                        .log("SQL: {{sql.insertPayment}}")
                        .to("sql:{{sql.insertPayment}}")
                        .log("Payment created successfully");
                        
                from("direct:updatePayment")
                        .log("Headers antes de actualizar en SQL: paymentId=${header.paymentId}, status=${header.status}")
                        .log("SQL: {{sql.updatePayment}}")
                        .to("sql:{{sql.updatePayment}}")
                        .log("Payment updated for orderId: ${header.orderId} with paymentId: ${header.paymentId}");
        }
}