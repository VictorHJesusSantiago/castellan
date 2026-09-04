package io.castellan.apm.core;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A single, mutable, in-flight (or just-finished) unit of traced work. This is the object woven-in
 * bytecode holds a reference to for the duration of one instrumented method call — {@code
 * AgentBridge.start(...)} returns one, the injected code calls {@link #setAttribute} on it zero or
 * more times, and the injected {@code finally} block calls {@link #end()} (via {@code
 * AgentBridge.end(...)}) exactly once.
 *
 * <p><b>Thread affinity, not thread safety:</b> a given {@code Span} is created, mutated, and ended
 * entirely by the one thread executing the instrumented method — nothing here is meant to be
 * handed to another thread while still open. {@link #attributes} is still a {@link
 * ConcurrentHashMap} defensively (cheap insurance against a future async instrumentation point
 * that legitimately spans threads, e.g. a callback-based HTTP client), not because concurrent
 * mutation is an expected/supported usage.
 *
 * <p>{@link #end()} is idempotent by construction (guarded by {@link #ended}) because the
 * try/finally bytecode this class exists to support always calls it on both the normal-return path
 * <em>and</em> (once, via the exception handler) the throwing path — a bug in that scaffolding
 * should not corrupt an already-recorded duration.
 */
public final class Span {

    private final String traceId;
    private final String spanId;
    private final String parentSpanId;
    private final String name;
    private final SpanKind kind;
    private final long startEpochMillis;
    private final long startNanos;
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    private volatile boolean ended;
    private volatile long endEpochMillis;
    private volatile long endNanos;
    private volatile SpanStatus status = SpanStatus.OK;
    private volatile String errorMessage;
    private volatile String errorStackTrace;

    Span(String traceId, String spanId, String parentSpanId, String name, SpanKind kind) {
        this.traceId = Objects.requireNonNull(traceId, "traceId");
        this.spanId = Objects.requireNonNull(spanId, "spanId");
        this.parentSpanId = parentSpanId;
        this.name = Objects.requireNonNull(name, "name");
        this.kind = Objects.requireNonNull(kind, "kind");
        this.startEpochMillis = System.currentTimeMillis();
        this.startNanos = System.nanoTime();
    }

    public String traceId() {
        return traceId;
    }

    public String spanId() {
        return spanId;
    }

    public String parentSpanId() {
        return parentSpanId;
    }

    public String name() {
        return name;
    }

    public SpanKind kind() {
        return kind;
    }

    public boolean isEnded() {
        return ended;
    }

    public Span setAttribute(String key, String value) {
        if (key != null && value != null) {
            attributes.put(key, value);
        }
        return this;
    }

    /**
     * Marks this span as failed. Deliberately takes the {@link Throwable} itself (not just a
     * message) so the injected {@code catch (Throwable t)} handler around an instrumented method
     * body can pass it straight through without the woven bytecode having to know anything about
     * how errors are recorded — that decision (what to keep, what to summarize) lives here in
     * ordinary, testable Java, exactly per the "bridge methods only" rule this whole module exists
     * to uphold.
     */
    public Span recordError(Throwable error) {
        if (error == null) {
            return this;
        }
        this.status = SpanStatus.ERROR;
        this.errorMessage = error.getClass().getName() + ": " + error.getMessage();
        StringWriter sw = new StringWriter();
        error.printStackTrace(new PrintWriter(sw));
        this.errorStackTrace = sw.toString();
        return this;
    }

    public synchronized void end() {
        if (ended) {
            return;
        }
        this.endNanos = System.nanoTime();
        this.endEpochMillis = System.currentTimeMillis();
        this.ended = true;
    }

    /** An immutable, exportable snapshot. Only meaningful after {@link #end()} has been called. */
    public SpanData snapshot() {
        long duration = ended ? (endNanos - startNanos) : 0L;
        long endedAtMillis = ended ? endEpochMillis : startEpochMillis;
        return new SpanData(
                traceId,
                spanId,
                parentSpanId,
                name,
                kind,
                status,
                errorMessage,
                errorStackTrace,
                startEpochMillis,
                endedAtMillis,
                duration,
                new LinkedHashMap<>(attributes));
    }
}
