package io.castellan.apm.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.castellan.apm.core.export.SpanExporter;
import io.castellan.apm.core.sampling.AdaptiveSampler;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SpanTest {

    @Test
    void attributesAndErrorSurviveIntoTheSnapshot() {
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(1000), spans -> { });
        Span span = tracer.startSpan("SELECT", SpanKind.CLIENT);
        span.setAttribute("db.statement", "SELECT 1");
        span.recordError(new IllegalStateException("boom"));
        tracer.endSpan(span);

        SpanData data = span.snapshot();

        assertThat(data.attributes()).containsEntry("db.statement", "SELECT 1");
        assertThat(data.status()).isEqualTo(SpanStatus.ERROR);
        assertThat(data.errorMessage()).contains("boom");
        assertThat(data.errorStackTrace()).contains("IllegalStateException");
    }

    @Test
    void endIsIdempotentDurationDoesNotChangeOnASecondCall() throws InterruptedException {
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(1000), spans -> { });
        Span span = tracer.startSpan("op", SpanKind.INTERNAL);
        span.end();
        long firstDuration = span.snapshot().durationNanos();

        Thread.sleep(5);
        span.end();
        long secondDuration = span.snapshot().durationNanos();

        assertThat(secondDuration).isEqualTo(firstDuration);
    }

    @Test
    void nullAttributeKeyOrValueIsIgnoredRatherThanThrowing() {
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(1000), spans -> { });
        Span span = tracer.startSpan("op", SpanKind.INTERNAL);

        span.setAttribute(null, "value");
        span.setAttribute("key", null);
        span.end();

        assertThat(span.snapshot().attributes()).isEmpty();
    }

    @Test
    void okStatusByDefaultWhenNoErrorRecorded() {
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(1000), spans -> { });
        Span span = tracer.startSpan("op", SpanKind.INTERNAL);
        span.end();

        assertThat(span.snapshot().status()).isEqualTo(SpanStatus.OK);
        assertThat(span.snapshot().errorMessage()).isNull();
    }

    @Test
    void snapshotIsAnIndependentCopyOfAttributes() {
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(1000), spans -> { });
        Span span = tracer.startSpan("op", SpanKind.INTERNAL);
        span.setAttribute("a", "1");
        SpanData before = span.snapshot();
        span.setAttribute("b", "2");

        assertThat(before.attributes()).doesNotContainKey("b");
    }

    /** Exercises the exporter SPI end to end with a trivial capturing implementation. */
    @Test
    void endedSpanIsHandedToTheConfiguredExporter() {
        List<SpanData> captured = new ArrayList<>();
        SpanExporter capturing = captured::addAll;
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(1000), capturing);

        Span span = tracer.startSpan("op", SpanKind.SERVER);
        tracer.endSpan(span);

        assertThat(captured).hasSize(1);
        assertThat(captured.get(0).name()).isEqualTo("op");
        assertThat(captured.get(0).kind()).isEqualTo(SpanKind.SERVER);
    }
}
