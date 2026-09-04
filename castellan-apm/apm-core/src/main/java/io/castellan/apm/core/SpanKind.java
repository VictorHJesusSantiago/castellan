package io.castellan.apm.core;

/**
 * What role this span plays in a call between two participants, mirroring the distinction the
 * W3C Trace Context / OpenTelemetry data model draws for the same reason: a {@code CLIENT} span
 * (the caller's side of an outbound call) and the corresponding {@code SERVER} span (the callee's
 * side of the same logical call, in a different process, joined only by a shared trace id and a
 * parent/child span id link) are what {@code apm-collector}'s service-map derivation pattern-matches
 * on to figure out "which service called which" — see
 * {@code io.castellan.apm.collector.servicemap.ServiceMapService}. {@code INTERNAL} is everything
 * else: a span that neither crosses a process boundary nor represents the boundary itself (e.g. a
 * JDBC call, which is "outbound" in the sense that it leaves the process but is deliberately
 * modeled as {@code CLIENT} anyway — see {@link #CLIENT}'s javadoc).
 */
public enum SpanKind {

    /**
     * The calling side of an operation that crosses a process/component boundary. Used for both
     * outbound HTTP calls and JDBC calls — a database is "another service" from the tracing
     * model's point of view, even though Castellan does not instrument the database side to
     * produce a matching {@code SERVER} span for it.
     */
    CLIENT,

    /** The receiving side of an inbound call that crossed a process boundary (e.g. a Spring MVC handler). */
    SERVER,

    /** Work that is neither a cross-process call nor the receiving end of one. */
    INTERNAL
}
