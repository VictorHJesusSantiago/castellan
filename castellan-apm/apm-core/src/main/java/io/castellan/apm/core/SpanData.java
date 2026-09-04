package io.castellan.apm.core;

import java.util.Map;

/**
 * The immutable, exported shape of a finished {@link Span} — what {@link Span#snapshot()} produces,
 * what a {@link io.castellan.apm.core.export.SpanExporter} batches and ships, and the wire format
 * {@code apm-collector} deserializes ({@code apm-collector} depends on {@code apm-core}, so it uses
 * this record directly rather than duplicating an equivalent DTO). Kept separate from {@link Span}
 * deliberately: {@code Span} is a mutable, thread-affine, in-flight object mutated by injected
 * bytecode over the lifetime of one instrumented method call; nothing about that belongs in what
 * gets serialized to JSON and handed to a different process.
 *
 * <p>{@code durationNanos} is computed from {@link System#nanoTime()} deltas (monotonic, immune to
 * wall-clock adjustments) — never from {@code endEpochMillis - startEpochMillis}, which is
 * millisecond-resolution and, on a machine whose clock is corrected mid-span (NTP slew, VM
 * migration), can even go negative. {@code startEpochMillis}/{@code endEpochMillis} exist purely
 * for human-readable absolute timestamps in the collector UI/API, not for measuring elapsed time.
 */
public record SpanData(
        String traceId,
        String spanId,
        String parentSpanId,
        String name,
        SpanKind kind,
        SpanStatus status,
        String errorMessage,
        String errorStackTrace,
        long startEpochMillis,
        long endEpochMillis,
        long durationNanos,
        Map<String, String> attributes) {

    public SpanData {
        attributes = Map.copyOf(attributes);
    }
}
