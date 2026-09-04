package io.castellan.apm.core;

import static org.assertj.core.api.Assertions.assertThat;

import io.castellan.apm.core.sampling.AdaptiveSampler;
import org.junit.jupiter.api.Test;

/** Context propagation: same-thread nesting via the span stack, and cross-process continuation via a parsed {@link TraceParent}. */
class TracerTest {

    private Tracer newTracer() {
        return new Tracer(AdaptiveSampler.withBudget(10_000), spans -> { });
    }

    @Test
    void rootSpanHasNoParentAndAFreshTraceId() {
        Tracer tracer = newTracer();

        Span root = tracer.startSpan("handle", SpanKind.SERVER);

        assertThat(root.parentSpanId()).isNull();
        assertThat(root.traceId()).hasSize(32);
        assertThat(root.spanId()).hasSize(16);
    }

    @Test
    void nestedSpanOnSameThreadIsParentedToTheOpenSpan() {
        Tracer tracer = newTracer();

        Span outer = tracer.startSpan("handle", SpanKind.SERVER);
        Span inner = tracer.startSpan("query", SpanKind.CLIENT);

        assertThat(inner.traceId()).isEqualTo(outer.traceId());
        assertThat(inner.parentSpanId()).isEqualTo(outer.spanId());
    }

    @Test
    void currentRevertsToOuterSpanAfterInnerSpanEnds() {
        Tracer tracer = newTracer();

        Span outer = tracer.startSpan("handle", SpanKind.SERVER);
        Span inner = tracer.startSpan("query", SpanKind.CLIENT);
        tracer.endSpan(inner);

        assertThat(tracer.current()).contains(outer);

        tracer.endSpan(outer);
        assertThat(tracer.current()).isEmpty();
    }

    @Test
    void threeLevelsOfNestingParentCorrectlyAndUnwindInOrder() {
        Tracer tracer = newTracer();

        Span a = tracer.startSpan("a", SpanKind.SERVER);
        Span b = tracer.startSpan("b", SpanKind.INTERNAL);
        Span c = tracer.startSpan("c", SpanKind.CLIENT);

        assertThat(c.parentSpanId()).isEqualTo(b.spanId());
        assertThat(b.parentSpanId()).isEqualTo(a.spanId());
        assertThat(a.traceId()).isEqualTo(b.traceId()).isEqualTo(c.traceId());

        tracer.endSpan(c);
        assertThat(tracer.current()).contains(b);
        tracer.endSpan(b);
        assertThat(tracer.current()).contains(a);
        tracer.endSpan(a);
        assertThat(tracer.current()).isEmpty();
    }

    @Test
    void remoteParentContinuesTheCallersTraceInsteadOfStartingAFreshOne() {
        Tracer tracer = newTracer();
        TraceParent remote = TraceParent.sampled("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");

        Span serverSpan = tracer.startSpan("GET /orders", SpanKind.SERVER, remote);

        assertThat(serverSpan.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(serverSpan.parentSpanId()).isEqualTo("00f067aa0ba902b7");
    }

    @Test
    void remoteParentTakesPriorityOverAStaleLocalStackEntry() {
        Tracer tracer = newTracer();
        Span staleLeftover = tracer.startSpan("previous-request-leak", SpanKind.SERVER);
        TraceParent remote = TraceParent.sampled("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7");

        Span serverSpan = tracer.startSpan("GET /orders", SpanKind.SERVER, remote);

        assertThat(serverSpan.traceId()).isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat(serverSpan.parentSpanId()).isEqualTo("00f067aa0ba902b7");
        assertThat(serverSpan.traceId()).isNotEqualTo(staleLeftover.traceId());
    }

    @Test
    void unsampledStartReturnsNullAndEndSpanToleratesIt() {
        Tracer tracer = new Tracer(AdaptiveSampler.withBudget(0.0000001), spans -> { });

        Span shouldBeDropped = tracer.startSpan("a", SpanKind.INTERNAL);

        assertThat(shouldBeDropped).isNull();
        tracer.endSpan(null);
    }
}
