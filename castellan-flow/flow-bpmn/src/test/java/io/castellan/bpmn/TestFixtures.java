package io.castellan.bpmn;

import io.castellan.bpmn.model.Process;
import io.castellan.bpmn.parser.BpmnParser;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;

/** Loads the BPMN fixture files under {@code src/test/resources/bpmn/}. */
public final class TestFixtures {

    private TestFixtures() {
    }

    public static Process load(String resourceName) {
        try (InputStream in = TestFixtures.class.getResourceAsStream("/bpmn/" + resourceName)) {
            if (in == null) {
                throw new IllegalArgumentException("no such fixture: " + resourceName);
            }
            return new BpmnParser().parse(in);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
