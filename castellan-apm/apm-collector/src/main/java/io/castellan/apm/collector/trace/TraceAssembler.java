package io.castellan.apm.collector.trace;

import io.castellan.apm.core.SpanData;

import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Rebuilds the parent/child span tree for one trace from
 * {@link io.castellan.apm.collector.ingest.SpanRepository#findByTraceId}'s flat,
 * arbitrarily-ordered result — every span's own {@code parentSpanId} is the only structure
 * available (spans are ingested one small batch at a time, in whatever order they finished, not as
 * a pre-built tree).
 *
 * <p>A "root" is any span whose {@code parentSpanId} is {@code null}, <em>or</em> whose parent id
 * doesn't match any span actually present in this trace — the latter happens whenever an
 * unsampled ancestor span dropped a link in the chain (see {@code Tracer#startSpan}'s sampling
 * short-circuit: an unsampled span returns {@code null} and is never itself exported, but a
 * sampled child of it still carries that unsampled span's id as its {@code parentSpanId}), so
 * without this fallback such a child would simply vanish from the assembled tree instead of
 * surfacing as its own root.
 *
 * <p>{@link #buildNode} tracks already-visited span ids and refuses to descend into one twice,
 * defensively: {@code POST /spans} is fed by whatever a client sends, and a cyclic
 * {@code parentSpanId} chain (a client bug, or a malicious poster) must not turn a single
 * malformed trace into unbounded recursion.
 */
final class TraceAssembler {

    private TraceAssembler() {
    }

    static TraceView assemble(String traceId, List<SpanData> spans) {
        Set<String> spanIds = spans.stream().map(SpanData::spanId).collect(Collectors.toSet());
        Map<String, List<SpanData>> childrenByParent = spans.stream()
                .filter(s -> s.parentSpanId() != null && spanIds.contains(s.parentSpanId()))
                .collect(Collectors.groupingBy(SpanData::parentSpanId));

        List<SpanNode> roots = spans.stream()
                .filter(s -> s.parentSpanId() == null || !spanIds.contains(s.parentSpanId()))
                .sorted(Comparator.comparingLong(SpanData::startEpochMillis))
                .map(root -> buildNode(root, childrenByParent, new HashSet<>()))
                .toList();

        return new TraceView(traceId, roots, spans.size());
    }

    private static SpanNode buildNode(SpanData span, Map<String, List<SpanData>> childrenByParent, Set<String> visited) {
        if (!visited.add(span.spanId())) {
            return new SpanNode(span, List.of());
        }
        List<SpanNode> children = childrenByParent.getOrDefault(span.spanId(), List.of()).stream()
                .sorted(Comparator.comparingLong(SpanData::startEpochMillis))
                .map(child -> buildNode(child, childrenByParent, visited))
                .toList();
        return new SpanNode(span, children);
    }
}
