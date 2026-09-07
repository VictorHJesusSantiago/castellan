package io.castellan.flow.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The composition root: a plain Spring Boot web application exposing {@code flow-engine}'s
 * process/rule-set deployment, instance start/signal/compensate, and query operations over REST.
 * Schema migration is Spring Boot's own Flyway auto-configuration reading {@code
 * spring.flyway.locations} (the same convention {@code ledger-api}/{@code apm-collector} use) —
 * {@code flow-engine}'s {@code V1__init.sql} ships on its own classpath, so no separate migration
 * step is needed here.
 */
@SpringBootApplication
public class FlowApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowApplication.class, args);
    }
}
