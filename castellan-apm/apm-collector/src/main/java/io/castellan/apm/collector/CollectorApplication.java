package io.castellan.apm.collector;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * The composition root: a plain Spring Boot web application that receives spans exported by
 * {@code apm-core}'s {@code HttpSpanExporter} (see {@link io.castellan.apm.collector.ingest.SpanIngestController}),
 * stores them (schema migrated by Spring Boot's own Flyway auto-configuration, driven by
 * {@code spring.flyway.locations} in {@code application.yml} — the same convention
 * {@code ledger-api} uses), and exposes trace lookup, service-map derivation, and latency
 * percentile queries over REST.
 */
@SpringBootApplication
public class CollectorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CollectorApplication.class, args);
    }
}
