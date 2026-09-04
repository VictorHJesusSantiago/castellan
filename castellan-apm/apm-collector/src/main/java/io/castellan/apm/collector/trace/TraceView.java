package io.castellan.apm.collector.trace;

import java.util.List;

/** One trace, reassembled into its parent/child span tree — {@code roots} ordinarily has exactly
 * one entry (the span with no {@code parentSpanId}), but is a list rather than a single node since
 * nothing in the data model actually forbids more than one (see {@link TraceAssembler}'s docs). */
public record TraceView(String traceId, List<SpanNode> roots, int spanCount) {

    public TraceView {
        roots = List.copyOf(roots);
    }
}
