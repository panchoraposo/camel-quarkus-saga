package com.redhat.demo.camel.saga.observability;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.camel.Exchange;
import org.jboss.logmanager.MDC;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import io.opentelemetry.context.propagation.TextMapGetter;
import io.opentelemetry.context.propagation.TextMapSetter;

public final class TbTelemetry {
    private static final Tracer TRACER = GlobalOpenTelemetry.getTracer("ticketblaster");

    private static final TextMapSetter<Map<String, Object>> MAP_SETTER = (carrier, key, value) -> {
        if (carrier != null && key != null && value != null) {
            carrier.put(key, value);
        }
    };

    private static final TextMapGetter<Exchange> EXCHANGE_GETTER = new TextMapGetter<>() {
        @Override
        public Iterable<String> keys(Exchange carrier) {
            if (carrier == null || carrier.getIn() == null || carrier.getIn().getHeaders() == null) {
                return java.util.List.of();
            }
            return carrier.getIn().getHeaders().keySet();
        }

        @Override
        public String get(Exchange carrier, String key) {
            if (carrier == null || carrier.getIn() == null) {
                return null;
            }
            Object v = carrier.getIn().getHeader(key);
            if (v == null) {
                return null;
            }
            if (v instanceof byte[]) {
                return new String((byte[]) v, StandardCharsets.UTF_8);
            }
            return String.valueOf(v);
        }
    };

    private TbTelemetry() {
    }

    public static void startKafkaConsumerSpan(Exchange exchange, String spanName) {
        Context parent = GlobalOpenTelemetry.getPropagators().getTextMapPropagator().extract(Context.root(), exchange, EXCHANGE_GETTER);
        Span span = TRACER.spanBuilder(spanName).setSpanKind(SpanKind.CONSUMER).setParent(parent).startSpan();
        Scope scope = span.makeCurrent();

        exchange.setProperty("tb.otel.span", span);
        exchange.setProperty("tb.otel.scope", scope);
        putMdc(exchange, span);
    }

    public static void injectTraceContextIntoHeaders(Exchange exchange) {
        GlobalOpenTelemetry.getPropagators().getTextMapPropagator().inject(Context.current(), exchange.getIn().getHeaders(), MAP_SETTER);
    }

    public static void endSpanAndClearMdc(Exchange exchange) {
        Scope scope = exchange.getProperty("tb.otel.scope", Scope.class);
        if (scope != null) {
            scope.close();
        }
        Span span = exchange.getProperty("tb.otel.span", Span.class);
        if (span != null) {
            span.end();
        }
        clearMdc();
    }

    private static void putMdc(Exchange exchange, Span span) {
        if (span != null && span.getSpanContext().isValid()) {
            MDC.put("traceId", span.getSpanContext().getTraceId());
            MDC.put("spanId", span.getSpanContext().getSpanId());
        }
        if (exchange != null && exchange.getIn() != null) {
            Object orderId = exchange.getIn().getHeader("orderId");
            if (orderId != null) {
                MDC.put("orderId", String.valueOf(orderId));
            }
            Object sagaId = exchange.getIn().getHeader("sagaId");
            if (sagaId != null) {
                MDC.put("sagaId", String.valueOf(sagaId));
            }
            Object paymentId = exchange.getIn().getHeader("paymentId");
            if (paymentId != null) {
                MDC.put("paymentId", String.valueOf(paymentId));
            }
        }
    }

    private static void clearMdc() {
        MDC.remove("traceId");
        MDC.remove("spanId");
        MDC.remove("orderId");
        MDC.remove("sagaId");
        MDC.remove("paymentId");
    }
}

