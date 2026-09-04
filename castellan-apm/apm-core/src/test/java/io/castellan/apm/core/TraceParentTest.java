package io.castellan.apm.core;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Verified against the W3C Trace Context spec's own worked example
 * (https://www.w3.org/TR/trace-context/#traceparent-header-field-values), not just against ids
 * this codebase generated itself — the whole point of the header is interop with other
 * implementations, so round-tripping only our own output would not prove format compliance.
 */
class TraceParentTest {

    private static final String SPEC_EXAMPLE = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01";

    @Test
    void parsesTheSpecsWorkedExample() {
        TraceParent parsed = TraceParent.parse(SPEC_EXAMPLE).orElseThrow();

        assertThat(parsed.version()).isEqualTo("00");
        assertThat(parsed.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(parsed.parentId()).isEqualTo("00f067aa0ba902b7");
        assertThat(parsed.traceFlags()).isEqualTo("01");
    }

    @Test
    void formatRoundTripsTheSpecsWorkedExampleExactly() {
        TraceParent parsed = TraceParent.parse(SPEC_EXAMPLE).orElseThrow();

        assertThat(parsed.format()).isEqualTo(SPEC_EXAMPLE);
    }

    @Test
    void sampledFactoryProducesAWellFormedHeaderValue() {
        TraceParent tp = TraceParent.sampled("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");

        assertThat(tp.format()).isEqualTo(SPEC_EXAMPLE);
    }

    @Test
    void rejectsNullHeader() {
        assertThat(TraceParent.parse(null)).isEmpty();
    }

    @Test
    void rejectsWrongFieldCount() {
        assertThat(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7")).isEmpty();
    }

    @Test
    void rejectsUnsupportedVersion() {
        assertThat(TraceParent.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    void rejectsAllZeroTraceId() {
        assertThat(TraceParent.parse("00-00000000000000000000000000000000-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    void rejectsAllZeroParentId() {
        assertThat(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-0000000000000000-01")).isEmpty();
    }

    @Test
    void rejectsWrongLengthTraceId() {
        assertThat(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e47-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    void rejectsUppercaseHex() {
        assertThat(TraceParent.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01")).isEmpty();
    }

    @Test
    void emptyOptionalOnGarbageNeverThrows() {
        Optional<TraceParent> result = TraceParent.parse("not-a-traceparent-header-at-all");

        assertThat(result).isEmpty();
    }
}
