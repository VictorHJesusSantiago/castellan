package io.castellan.apm.core;

import io.castellan.apm.core.export.SpanExporter;
import io.castellan.apm.core.sampling.AdaptiveSampler;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Owns context propagation (the per-thread "current span" stack, so a span opened while another
 * is already open on the same thread is correctly parented to it) and sampling/export wiring.
 * {@link AgentBridge} is the only intended caller from woven bytecode — application/instrumentation
 * code never touches a {@code Tracer} directly, which is what lets {@code AgentBridge}'s method
 * signatures stay stable while everything behind them (this class) evolves freely.
 *
 * <h2>Why a stack, not a single "current span" reference</h2>
 *
 * An instrumented method can call another instrumented method on the same thread (a Spring MVC
 * handler that issues a JDBC query is the common case) — the JDBC span's parent must be the
 * still-open handler span, and when the JDBC span ends, "current" must revert to the handler span,
 * not to nothing. A {@link Deque} used as a stack (push on start, pop on end) gives exactly that
 * LIFO nesting for free; a single mutable field would lose the handler span the moment the JDBC
 * span overwrote it.
 */
public final class Tracer {

    private final AdaptiveSampler sampler;
    private final SpanExporter exporter;
    private final ThreadLocal<Deque<Span>> currentStack = ThreadLocal.withInitial(ArrayDeque::new);

    public Tracer(AdaptiveSampler sampler, SpanExporter exporter) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
        this.exporter = Objects.requireNonNull(exporter, "exporter");
    }

    /** Starts a span parented to whatever is currently open on this thread (or a fresh trace if nothing is). */
    public Span startSpan(String name, SpanKind kind) {
        return startSpan(name, kind, null);
    }

    /**
     * Starts a span, optionally continuing a trace whose identity arrived from another process via
     * a parsed {@code traceparent} header (inbound HTTP). When {@code remoteParent} is given it
     * takes priority over whatever happens to already be open on this thread — an inbound HTTP
     * request handler thread should never accidentally nest under a stale span from a previous,
     * unrelated request the thread-pool thread handled earlier.
     */
    public Span startSpan(String name, SpanKind kind, TraceParent remoteParent) {
        if (!sampler.shouldSample()) {
            return null;
        }
        Deque<Span> stack = currentStack.get();
        Span localParent = stack.peek();

        String traceId;
        String parentSpanId;
        if (remoteParent != null) {
            traceId = remoteParent.traceId();
            parentSpanId = remoteParent.parentId();
        } else if (localParent != null) {
            traceId = localParent.traceId();
            parentSpanId = localParent.spanId();
        } else {
            traceId = IdGenerator.newTraceId();
            parentSpanId = null;
        }

        Span span = new Span(traceId, IdGenerator.newSpanId(), parentSpanId, name, kind);
        stack.push(span);
        return span;
    }

    /**
     * Ends the span, pops it off this thread's stack, and hands its snapshot to the exporter.
     * Tolerates {@code span == null} (an unsampled span from {@link #startSpan}) as a no-op, and
     * tolerates ending a span that is not the top of the stack (falls back to a linear removal) —
     * both defensive against a bug in the woven bytecode's try/finally scaffolding rather than an
     * expected code path.
     */
    public void endSpan(Span span) {
        if (span == null) {
            return;
        }
        span.end();
        Deque<Span> stack = currentStack.get();
        if (span.equals(stack.peek())) {
            stack.pop();
        } else {
            stack.remove(span);
        }
        exporter.export(List.of(span.snapshot()));
    }

    public Optional<Span> current() {
        return Optional.ofNullable(currentStack.get().peek());
    }
}
