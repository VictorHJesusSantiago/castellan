package io.castellan.apm.collector.trace;

import io.castellan.apm.core.SpanData;

import java.util.List;

/** One span plus its children, recursively — the tree {@link TraceAssembler} rebuilds from a
 * trace's flat, unordered list of {@link SpanData} using each span's {@code parentSpanId}. */
public record SpanNode(SpanData span, List<SpanNode> children) {

    public SpanNode {
        children = List.copyOf(children);
    }
}
