package com.example.webapp;

import jakarta.servlet.http.HttpServletRequest;

/**
 * The interface {@link SpringMvcInstrumentationRuleTest} programs against after loading the woven
 * {@link FakeOrderController} into a fresh {@link ClassLoader} — see {@code
 * JdbcInstrumentationRuleTest}'s equivalent pattern for why an interface (resolved via classloader
 * delegation to the same loader the test itself uses) is needed rather than casting to the
 * concrete, redefined class directly.
 */
public interface OrderApi {

    String getOrder(String id);

    String createOrderWithRequest(HttpServletRequest request);

    String notMapped(String x);

    String broken();
}
