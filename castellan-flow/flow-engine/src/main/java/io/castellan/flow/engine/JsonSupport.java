package io.castellan.flow.engine;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** The single {@link ObjectMapper} configuration used to serialize {@link
 * io.castellan.bpmn.exec.ProcessState} (and {@link JsonRuleDefinition} lists) to and from the
 * {@code CLOB} columns in H2 — record support is native to Jackson 2.17+, {@link JavaTimeModule}
 * is what lets {@code Instant} fields (timer due times) round-trip as ISO-8601 rather than epoch
 * arrays. */
final class JsonSupport {

    static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private JsonSupport() {
    }
}
