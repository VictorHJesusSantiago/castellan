package com.example.webapp;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

/**
 * A tiny stand-in Spring MVC controller, annotated with the real {@code org.springframework.web}
 * annotations (this module's test scope depends on {@code spring-web} precisely so fixtures like
 * this one are genuine, not stand-ins for the annotation types themselves — see {@code
 * apm-agent/pom.xml}). Covers all three cases {@code SpringMvcInstrumentationRule} needs to
 * distinguish: a mapped method with no {@code HttpServletRequest} parameter ({@link #getOrder}), a
 * mapped method that does have one ({@link #createOrderWithRequest}), and an unmapped method that
 * must never be woven at all ({@link #notMapped}).
 */
public final class FakeOrderController implements OrderApi {

    @GetMapping("/orders/{id}")
    @Override
    public String getOrder(String id) {
        return "order:" + id;
    }

    @PostMapping("/orders")
    @Override
    public String createOrderWithRequest(HttpServletRequest request) {
        return "created, inbound traceparent seen by handler=" + request.getHeader("traceparent");
    }

    @Override
    public String notMapped(String x) {
        return "plain:" + x;
    }

    @GetMapping("/orders/broken")
    @Override
    public String broken() {
        throw new IllegalStateException("handler failed");
    }
}
