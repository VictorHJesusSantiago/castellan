package io.castellan.apm.core;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The W3C Trace Context {@code traceparent} header
 * (<a href="https://www.w3.org/TR/trace-context/#traceparent-header">spec</a>): the only thing
 * that carries trace identity across a process boundary. Everything else in this package
 * (the {@code ThreadLocal} span stack in {@link Tracer}) works purely in-process; this type is
 * what an outbound HTTP call serializes into a request header, and what an inbound HTTP handler
 * parses back out to continue the caller's trace instead of starting a new one.
 *
 * <p>Wire format: {@code version-traceid-parentid-traceflags}, e.g. the spec's own worked example
 * {@code 00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01} — version {@code 00}, a 32-hex-char
 * trace id, a 16-hex-char parent (i.e. the sending span's own) id, and a 2-hex-char flags byte
 * (bit 0 = "sampled"). {@link TraceParentTest} asserts round-trip parse/format against exactly
 * that example value, not just against ids this class generated itself.
 *
 * <p><b>Scope cut:</b> the spec requires future (&gt;00) versions to be parsed leniently — extra
 * {@code -}-delimited fields ignored, unknown flag bits preserved — specifically so an old
 * implementation does not break a trace when a newer one adds fields. This class does not attempt
 * that forward-compatibility parsing; it accepts exactly the 4-field, version-{@code 00} form and
 * rejects everything else via {@link #parse}, returning {@link Optional#empty()} rather than
 * guessing. For a single-process-family agent (this one) that is a deliberate, stated
 * simplification, not an oversight.
 */
public record TraceParent(String version, String traceId, String parentId, String traceFlags) {

    private static final String VERSION_00 = "00";
    private static final String SAMPLED_FLAGS = "01";

    private static final Pattern TRACE_ID = Pattern.compile("[0-9a-f]{32}");
    private static final Pattern PARENT_ID = Pattern.compile("[0-9a-f]{16}");
    private static final Pattern FLAGS = Pattern.compile("[0-9a-f]{2}");
    private static final String ALL_ZERO_TRACE_ID = "0".repeat(32);
    private static final String ALL_ZERO_PARENT_ID = "0".repeat(16);

    public TraceParent {
        if (!VERSION_00.equals(version)) {
            throw new IllegalArgumentException("unsupported traceparent version: " + version);
        }
        requireValid(traceId, TRACE_ID, ALL_ZERO_TRACE_ID, "trace-id");
        requireValid(parentId, PARENT_ID, ALL_ZERO_PARENT_ID, "parent-id");
        if (!FLAGS.matcher(traceFlags).matches()) {
            throw new IllegalArgumentException("malformed trace-flags: " + traceFlags);
        }
    }

    private static void requireValid(String value, Pattern shape, String allZero, String field) {
        if (!shape.matcher(value).matches()) {
            throw new IllegalArgumentException("malformed " + field + ": " + value);
        }
        if (value.equals(allZero)) {
            throw new IllegalArgumentException(field + " must not be all zeroes");
        }
    }

    /** Builds the outbound header value for the currently-open span of {@code trace/parentSpanId}. */
    public static TraceParent sampled(String traceId, String parentSpanId) {
        return new TraceParent(VERSION_00, traceId, parentSpanId, SAMPLED_FLAGS);
    }

    /** Formats this value exactly as it belongs in an HTTP {@code traceparent} header. */
    public String format() {
        return version + "-" + traceId + "-" + parentId + "-" + traceFlags;
    }

    /**
     * Parses a {@code traceparent} header value. Returns {@link Optional#empty()} — never throws
     * — for anything malformed, so a caller (inbound HTTP instrumentation) can uniformly treat
     * "no header" and "garbage header" the same way: start a fresh trace.
     */
    public static Optional<TraceParent> parse(String header) {
        if (header == null) {
            return Optional.empty();
        }
        String[] parts = header.trim().split("-", -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            return Optional.of(new TraceParent(parts[0], parts[1], parts[2], parts[3]));
        } catch (IllegalArgumentException notValid) {
            return Optional.empty();
        }
    }
}
