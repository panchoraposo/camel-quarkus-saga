package com.redhat.demo.camel.saga.service;

import java.nio.charset.StandardCharsets;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Map;

import org.apache.camel.Exchange;
import org.apache.camel.Header;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.faulttolerance.CircuitBreaker;
import org.eclipse.microprofile.faulttolerance.Fallback;
import org.eclipse.microprofile.faulttolerance.Retry;
import org.eclipse.microprofile.faulttolerance.Timeout;
import org.jboss.logging.Logger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.redhat.demo.camel.saga.model.OrderDto;
import com.redhat.demo.entity.User;

import io.quarkus.logging.Log;
import io.quarkus.runtime.annotations.RegisterForReflection;

import com.redhat.demo.camel.saga.repository.UserRepository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.transaction.Transactional;

@RegisterForReflection
@ApplicationScoped
@Named("orderService")
public class OrderService {

    private static final Logger LOG = Logger.getLogger(OrderService.class);

    @Inject
    ProducerTemplate producerTemplate;

    @Inject
    UserRepository userRepository;

    // Create order
    @Retry(maxRetries = 3, delay = 2, delayUnit = ChronoUnit.SECONDS)
    @CircuitBreaker(requestVolumeThreshold = 4, failureRatio = 0.75, successThreshold = 4)
    @Timeout(500)
    @Fallback(fallbackMethod = "orderFallback")
    public OrderDto createOrder(Exchange exchange) {
        String orderId = exchange.getMessage().getHeader("id", String.class);
        OrderDto order = exchange.getMessage().getBody(OrderDto.class);
        order.setOrderId(orderId);
        order.setOrderStatus("PENDING");
        order.setOrderMessage("Order " + order.getOrderId() + " created successfully.");
        return order;
    }

    public OrderDto orderFallback(Exchange exchange) {
        // Lógica en caso de que falle la creación de la orden
        OrderDto order = new OrderDto();
        String orderId = exchange.getMessage().getHeader("id", String.class);
        order.setOrderId(orderId);
        order.setOrderStatus("FAILED");
        order.setOrderMessage("Order could not be created.");
        return order;
    }

    @Transactional
    public OrderDto enrichUserFromBearer(@Header("Authorization") String authorization, OrderDto order) {
        if (order == null) {
            return null;
        }
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            return order;
        }

        String token = authorization.substring("Bearer ".length()).trim();
        String username = extractPreferredUsername(token);
        if (username == null || username.isBlank()) {
            return order;
        }

        User user = userRepository.find("username", username).firstResult();
        if (user != null) {
            order.setUserId(user.getId());
        }
        return order;
    }

    private static String extractPreferredUsername(String token) {
        try {
            String[] parts = token.split("\\.");
            if (parts.length < 2) {
                return null;
            }
            byte[] decoded = Base64.getUrlDecoder().decode(padBase64(parts[1]));
            String json = new String(decoded, StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> claims = new ObjectMapper().readValue(json, Map.class);
            Object preferred = claims.get("preferred_username");
            if (preferred instanceof String && !((String) preferred).isBlank()) {
                return (String) preferred;
            }
            Object upn = claims.get("upn");
            if (upn instanceof String && !((String) upn).isBlank()) {
                return (String) upn;
            }
            Object email = claims.get("email");
            if (email instanceof String && !((String) email).isBlank()) {
                return (String) email;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String padBase64(String s) {
        int mod = s.length() % 4;
        if (mod == 0) return s;
        return s + "====".substring(mod);
    }

    public OrderDto updateOrder(OrderDto order) {
        if (order.getPaymentStatus() != null && order.getPaymentStatus().equalsIgnoreCase("COMPLETED") && order.getSeatStatus() != null && order.getSeatStatus().equalsIgnoreCase("RESERVED")) {
            //order.setOrderStatus("COMPLETED");
            //order.setOrderMessage("Order: " + order.getOrderId() + " completed.");
            //updateUserBudget(order);
        }
        return order;
    }
    
    public void cancelOrder(OrderDto order) {
        if (order == null) {
            LOG.error("Intento de cancelar una orden nula.");
            return;
        }

        String orderId = order.getOrderId();
        LOG.info("COMPENSATION -> Cancelling order " + orderId);

        order.setOrderMessage("Order " + orderId + " cancelled due to an issue.");
        order.setOrderStatus("CANCELLED");
    }

    @Transactional
    public OrderDto reserveBudget(OrderDto order) {
        if (order == null) {
            return null;
        }
        if (order.getBudgetReserved() != null && order.getBudgetReserved().booleanValue()) {
            return order;
        }
        if (order.getUserId() == null) {
            order.setOrderStatus("FAILED");
            order.setOrderMessage("Missing userId.");
            return order;
        }
        if (order.getPrice() == null) {
            order.setOrderStatus("FAILED");
            order.setOrderMessage("Missing price.");
            return order;
        }

        User user = userRepository.findById(order.getUserId());
        if (user == null) {
            order.setOrderStatus("FAILED");
            order.setOrderMessage("User not found.");
            return order;
        }

        Double budget = user.getBudget();
        if (budget == null) {
            order.setOrderStatus("FAILED");
            order.setOrderMessage("User budget missing.");
            return order;
        }
        if (budget < order.getPrice()) {
            order.setOrderStatus("FAILED");
            order.setOrderMessage("Insufficient budget.");
            return order;
        }

        Double newBudget = budget - order.getPrice();
        user.setBudget(newBudget);
        userRepository.persist(user);

        order.setBudgetReserved(Boolean.TRUE);
        Log.info("Budget reserved for userId=" + order.getUserId() + ". Before: " + budget + " After: " + newBudget);
        return order;
    }

    @Transactional
    public OrderDto refundBudget(OrderDto order) {
        if (order == null) {
            return null;
        }
        if (order.getBudgetReserved() == null || !order.getBudgetReserved().booleanValue()) {
            return order;
        }
        if (order.getUserId() == null || order.getPrice() == null) {
            return order;
        }

        User user = userRepository.findById(order.getUserId());
        if (user == null || user.getBudget() == null) {
            return order;
        }

        Double before = user.getBudget();
        Double after = before + order.getPrice();
        user.setBudget(after);
        userRepository.persist(user);

        order.setBudgetReserved(Boolean.FALSE);
        Log.info("Budget refunded for userId=" + order.getUserId() + ". Before: " + before + " After: " + after);
        return order;
    }

}