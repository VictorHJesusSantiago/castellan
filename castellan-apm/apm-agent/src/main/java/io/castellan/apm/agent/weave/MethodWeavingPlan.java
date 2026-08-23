package io.castellan.apm.agent.weave;

import io.castellan.apm.core.SpanKind;
import java.util.List;

/**
 * Everything {@link SpanWeavingMethodVisitor} needs to know to weave <em>one specific method</em>,
 * decided once (by whichever {@link MethodInstrumentationRule} matched) during the cheap probe
 * pass and carried, unchanged, into the full weaving pass. Splitting "decide what to weave" (rule
 * logic, reasoning about interfaces/annotations/descriptors) from "how to emit the bytecode for a
 * decision" (this record plus {@link SpanWeavingMethodVisitor}) is what lets JDBC/HTTP/Spring MVC
 * share one bytecode-emission implementation instead of each hand-rolling its own try/finally
 * scaffolding.
 *
 * @param spanKind                        {@code CLIENT} for JDBC/outbound-HTTP, {@code SERVER} for an inbound MVC handler.
 * @param spanName                        computed once at weave time (never at woven-code runtime) — see each rule.
 * @param httpServletRequestArgIndex      zero-based method-parameter index of a {@code HttpServletRequest} argument
 *                                        whose {@code traceparent} header should continue the caller's trace, or
 *                                        {@code -1} if this method has none (a fresh trace is started instead).
 * @param attributeCaptures               method-argument values to copy onto the span as string attributes.
 * @param injectOutboundTraceparentHeader whether to call {@code setRequestProperty("traceparent", ...)} on
 *                                        {@code this} before the original method body runs (the {@code HttpURLConnection} case).
 */
public record MethodWeavingPlan(
        SpanKind spanKind,
        String spanName,
        int httpServletRequestArgIndex,
        List<AttributeCapture> attributeCaptures,
        boolean injectOutboundTraceparentHeader) {

    /** One {@code AgentBridge.setAttribute(span, key, arg-N)} call to emit in the woven prologue. */
    public record AttributeCapture(String attributeKey, int argIndex) {
    }

    /** A JDBC call: {@code CLIENT} span, no remote-parent extraction, one attribute capture (the SQL text argument). */
    public static MethodWeavingPlan jdbc(String spanName, int sqlArgIndex) {
        return new MethodWeavingPlan(
                SpanKind.CLIENT, spanName, -1, List.of(new AttributeCapture("db.statement", sqlArgIndex)), false);
    }

    /** An outbound {@code HttpURLConnection.connect()} call: {@code CLIENT} span, injects the outbound {@code traceparent} header. */
    public static MethodWeavingPlan outboundHttp(String spanName) {
        return new MethodWeavingPlan(SpanKind.CLIENT, spanName, -1, List.of(), true);
    }

    /** A Spring MVC handler method: {@code SERVER} span, continuing the caller's trace if a {@code HttpServletRequest} argument is present. */
    public static MethodWeavingPlan serverHandler(String spanName, int httpServletRequestArgIndex) {
        return new MethodWeavingPlan(SpanKind.SERVER, spanName, httpServletRequestArgIndex, List.of(), false);
    }
}
