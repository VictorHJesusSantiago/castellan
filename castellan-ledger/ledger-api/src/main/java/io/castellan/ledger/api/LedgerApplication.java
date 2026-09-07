package io.castellan.ledger.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The composition root: a plain Spring Boot web application wiring {@code ledger-application}'s
 * handlers/orchestrator/fraud engine to {@code ledger-infrastructure}'s JDBC adapters (see
 * {@link io.castellan.ledger.api.config.LedgerBeanConfiguration}) and exposing them over REST.
 * {@code @EnableScheduling} drives {@code OutboxRelay} on a fixed delay (see
 * {@link io.castellan.ledger.api.config.OutboxSchedulingConfiguration}) -- the outbox pattern's
 * whole point is that publishing is a separate, independently-retryable process from the ledger
 * append itself, not something triggered inline on the request thread.
 */
@SpringBootApplication
@EnableScheduling
public class LedgerApplication {

    public static void main(String[] args) {
        SpringApplication.run(LedgerApplication.class, args);
    }
}
